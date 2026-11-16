package app.dustbird.mail.ui.compose

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.identity.DraftSeed
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.repo.AccountWithIdentities
import app.dustbird.mail.data.repo.DraftContent
import app.dustbird.mail.sync.EnqueueResult
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.nav.ComposeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RecipientField {
    var chips by mutableStateOf<List<EmailAddress>>(emptyList())
        private set
    val input = TextFieldState()

    fun commit(flush: Boolean) {
        val text = input.text.toString()
        val split = RecipientInput.split(text, flush)
        add(split.tokens.map(RecipientInput::parse))
        if (split.rest != text) input.setTextAndPlaceCursorAtEnd(split.rest)
    }

    fun add(list: List<EmailAddress>) {
        val seen = chips.map { Addresses.normalize(it.address) }.toMutableSet()
        val fresh = list.filter { seen.add(Addresses.normalize(it.address)) }
        if (fresh.isNotEmpty()) chips = chips + fresh
    }

    fun replaceAll(list: List<EmailAddress>) {
        chips = emptyList()
        add(list)
        input.setTextAndPlaceCursorAtEnd("")
    }

    fun remove(chip: EmailAddress) {
        chips = chips - chip
    }

    fun removeLast() {
        chips = chips.dropLast(1)
    }

    fun edit(chip: EmailAddress) {
        commit(flush = true)
        remove(chip)
        input.setTextAndPlaceCursorAtEnd(chip.address)
    }

    fun effective(): List<EmailAddress> {
        val pending = RecipientInput.split(input.text.toString(), flush = true).tokens
            .map(RecipientInput::parse)
            .filter(RecipientInput::isValid)
        return (chips + pending).distinctBy { Addresses.normalize(it.address) }
    }
}

enum class LoadState { Loading, Ready, NoAccount }

enum class ComposeDialog { EmptySubject, SenderCheck, Discard }

data class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList())

private data class OutboxSnapshot(val state: SendState, val failure: String?)

class ComposeViewModel(
    private val container: AppContainer,
    private val application: Application,
    private val args: ComposeArgs,
    private val handle: SavedStateHandle,
) : ViewModel() {
    val to = RecipientField()
    val cc = RecipientField()
    val bcc = RecipientField()
    val subject = TextFieldState()
    val body = TextFieldState()
    val events = Channel<UiMessage>(Channel.BUFFERED)

    var loadState by mutableStateOf(LoadState.Loading)
        private set
    var groups by mutableStateOf<List<AccountWithIdentities>>(emptyList())
        private set
    var identityId by mutableStateOf<Long?>(null)
        private set
    var senderConfirmed by mutableStateOf(true)
        private set
    var showCc by mutableStateOf(false)
    var showBcc by mutableStateOf(false)
    var sendAttempted by mutableStateOf(false)
        private set
    var dialog by mutableStateOf<ComposeDialog?>(null)
    var saving by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    private var processing by mutableStateOf(false)
    private var outbox by mutableStateOf<OutboxSnapshot?>(null)
    private var hasOutbox by mutableStateOf(handle.contains("outboxId"))

    val phase: SendPhase
        get() = when {
            !hasOutbox -> if (preparing) SendPhase.Progress(false) else SendPhase.Idle
            else -> SendPhase.of(outbox?.state, outbox?.failure, processing)
        }

    val recipientProblem: RecipientProblem
        get() = if (sendAttempted) RecipientCheck.of(to.effective(), cc.effective(), bcc.effective()) else RecipientProblem.NONE

    val selected: Pair<AccountWithIdentities, MailIdentity>?
        get() = identityId?.let(::lookup)

    val multipleAccounts: Boolean
        get() = groups.size > 1

    private val saveMutex = Mutex()
    private var lastSaved: DraftContent? = null
    private var lastSynced: DraftContent? = null
    private var inReplyTo: String? = null
    private var references: List<String> = emptyList()
    private var sealed = false
    private var discarded = false
    private var subjectAsked = false
    private var senderChecked = false

    private var draftId: Long?
        get() = handle["draftId"]
        set(value) {
            handle["draftId"] = value
        }

    private var outboxId: Long?
        get() = handle["outboxId"]
        set(value) {
            handle["outboxId"] = value
        }

    init {
        viewModelScope.launch {
            load()
            if (loadState == LoadState.Ready && outboxId == null) {
                listOf(to, cc, bcc).forEach { field ->
                    launch { snapshotFlow { field.input.text.toString() }.collect { field.commit(flush = false) } }
                }
                autosave()
            }
        }
    }

    private suspend fun autosave() {
        snapshotFlow { content() }.collectLatest { current ->
            delay(AUTOSAVE_DELAY_MILLIS)
            if (!sealed && current != lastSaved) {
                try {
                    persist(current)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    events.trySend(UiMessage(R.string.compose_autosave_failed))
                }
            }
        }
    }

    private suspend fun load() {
        val loaded = container.accounts.accountsWithIdentities.first()
        groups = loaded
        val identities = loaded.flatMap { it.identities }
        if (identities.isEmpty()) {
            loadState = LoadState.NoAccount
            return
        }
        viewModelScope.launch { container.accounts.accountsWithIdentities.collect { groups = it } }
        val resumedOutbox = outboxId
        if (resumedOutbox != null) {
            sealed = true
            identityId = identities.first().id
            observe(resumedOutbox)
            loadState = LoadState.Ready
            return
        }
        val fallbackId = container.accounts.appDefaultIdentityId.first()
            ?.takeIf { id -> identities.any { it.id == id } } ?: identities.first().id
        val stored = draftId?.let { container.drafts.load(it) }
            ?: args.draftId?.let { id -> container.drafts.load(id)?.also { draftId = id } }
        val start = when {
            stored != null -> {
                senderConfirmed = handle["senderConfirmed"] ?: true
                stored
            }
            args.mode != ComposeMode.NEW && args.sourceMessageId != null -> seed(identities, fallbackId)
            else -> null
        } ?: blank(identities, fallbackId)
        populate(start, identities, fallbackId)
        lastSaved = content()
        lastSynced = if (stored != null) lastSaved else null
        loadState = LoadState.Ready
    }

    private fun blank(identities: List<MailIdentity>, fallbackId: Long): DraftContent {
        val identity = identities.first { it.id == fallbackId }
        return DraftContent(null, identity.id, emptyList(), emptyList(), emptyList(), "", SignatureBlock.append("", identity.signature), null, emptyList())
    }

    private suspend fun seed(identities: List<MailIdentity>, fallbackId: Long): DraftContent? {
        val sourceId = args.sourceMessageId ?: return null
        val summary = container.mail.detail(sourceId).first()?.summary
        if (summary == null) {
            events.trySend(UiMessage(R.string.compose_source_missing))
            return null
        }
        val sender = summary.fromName?.takeIf { it.isNotBlank() } ?: summary.fromAddress ?: application.getString(R.string.compose_unknown_sender)
        val date = DATE_FORMAT.withLocale(Locale.getDefault()).format(Instant.ofEpochMilli(summary.sortDate).atZone(ZoneId.systemDefault()))
        val seed: DraftSeed? = if (args.mode == ComposeMode.FORWARD) {
            val from = summary.fromAddress?.let { address -> if (sender == address) address else "$sender <$address>" } ?: sender
            container.mail.forwardSeed(sourceId, application.getString(R.string.compose_forward_header, from, date, summary.subject))
        } else {
            container.mail.replySeed(sourceId, args.mode == ComposeMode.REPLY_ALL, application.getString(R.string.compose_reply_attribution, date, sender))
        }
        if (seed == null) {
            events.trySend(UiMessage(R.string.compose_source_missing))
            return null
        }
        val identity = identities.firstOrNull { it.id == seed.identityId } ?: identities.first { it.id == fallbackId }
        senderConfirmed = !seed.identityNeedsConfirmation
        handle["senderConfirmed"] = senderConfirmed
        return DraftContent(
            id = null,
            identityId = identity.id,
            to = seed.to,
            cc = seed.cc,
            bcc = emptyList(),
            subject = seed.subject,
            body = SignatureBlock.append(seed.body, identity.signature),
            inReplyTo = seed.inReplyTo,
            references = seed.references,
        )
    }

    private fun populate(content: DraftContent, identities: List<MailIdentity>, fallbackId: Long) {
        identityId = identities.firstOrNull { it.id == content.identityId }?.id ?: fallbackId
        to.replaceAll(content.to)
        cc.replaceAll(content.cc)
        bcc.replaceAll(content.bcc)
        showCc = content.cc.isNotEmpty()
        showBcc = content.bcc.isNotEmpty()
        subject.setTextAndPlaceCursorAtEnd(content.subject)
        body.edit {
            replace(0, length, content.body)
            placeCursorBeforeCharAt(0)
        }
        inReplyTo = content.inReplyTo
        references = content.references
    }

    private fun lookup(id: Long): Pair<AccountWithIdentities, MailIdentity>? =
        groups.firstNotNullOfOrNull { group -> group.identities.firstOrNull { it.id == id }?.let { group to it } }

    fun accountIndex(accountId: Long): Int = groups.indexOfFirst { it.account.id == accountId }.coerceAtLeast(0)

    fun content(): DraftContent = DraftContent(
        id = null,
        identityId = identityId ?: 0,
        to = to.effective(),
        cc = cc.effective(),
        bcc = bcc.effective(),
        subject = SubjectText.clean(subject.text),
        body = body.text.toString(),
        inReplyTo = inReplyTo,
        references = references,
    )

    private fun commitAll() {
        to.commit(flush = true)
        cc.commit(flush = true)
        bcc.commit(flush = true)
    }

    fun selectIdentity(newId: Long) {
        senderConfirmed = true
        handle["senderConfirmed"] = true
        val old = selected
        val new = lookup(newId) ?: return
        if (old?.second?.id == newId) return
        val oldSignature = old?.second?.signature.orEmpty()
        val current = body.text.toString()
        val swapped = SignatureBlock.swap(current, oldSignature, new.second.signature)
        if (swapped != current) {
            val common = current.commonPrefixWith(swapped).length
            body.edit { replace(common, current.length, swapped.substring(common)) }
        }
        identityId = newId
        if (old != null && old.first.account.id != new.first.account.id) {
            events.trySend(UiMessage(R.string.compose_account_changed, listOf(new.first.account.displayName)))
        }
    }

    fun confirmSender() {
        senderConfirmed = true
        handle["senderConfirmed"] = true
    }

    suspend fun saveLocal(): Long? {
        commitAll()
        return persist(content())
    }

    private suspend fun persist(content: DraftContent, ignoreSeal: Boolean = false): Long? = withContext(NonCancellable) {
        saveMutex.withLock {
            if (discarded || (sealed && !ignoreSeal)) return@withLock null
            val id = container.drafts.save(content.copy(id = draftId))
            draftId = id
            lastSaved = content
            id
        }
    }

    private fun background(block: suspend () -> Unit) {
        container.scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                events.trySend(UiMessage(R.string.compose_unexpected_error))
            }
        }
    }

    fun saveDraft() {
        if (sealed || saving) return
        commitAll()
        val current = content()
        saving = true
        background {
            try {
                val id = persist(current) ?: return@background
                val stored = container.drafts.syncToServer(id)
                if (stored) lastSynced = current
                val accountId = lookup(current.identityId)?.first?.account?.id
                val message = when {
                    stored -> R.string.compose_saved_server
                    accountId != null && container.db.folders().byRole(accountId, FolderRole.DRAFTS) == null -> R.string.compose_saved_no_folder
                    else -> R.string.compose_saved_server_failed
                }
                events.trySend(UiMessage(message))
            } finally {
                saving = false
            }
        }
    }

    fun leave(): Boolean {
        if (sealed || loadState != LoadState.Ready) return false
        commitAll()
        val current = content()
        val needsLocal = current != lastSaved
        val needsServer = draftId != null && current != lastSynced
        if (!needsLocal && !needsServer) return false
        background {
            val id = (if (needsLocal) persist(current) else draftId) ?: return@background
            if (container.drafts.syncToServer(id)) lastSynced = current
        }
        return true
    }

    fun flush() {
        if (sealed || loadState != LoadState.Ready) return
        commitAll()
        val current = content()
        if (current != lastSaved) background { persist(current) }
    }

    fun hasWork(): Boolean = draftId != null || content() != lastSaved

    fun discard() {
        sealed = true
        discarded = true
        background { saveMutex.withLock { draftId?.let { container.drafts.delete(it) } } }
    }

    fun send() {
        if (sealed || loadState != LoadState.Ready) return
        sendAttempted = true
        commitAll()
        val current = content()
        if (RecipientCheck.of(current.to, current.cc, current.bcc) != RecipientProblem.NONE) return
        val (group, identity) = lookup(current.identityId) ?: return
        if (!senderChecked && (!senderConfirmed || identity.verification == SendVerification.FAILED)) {
            dialog = ComposeDialog.SenderCheck
            return
        }
        if (current.subject.isBlank() && !subjectAsked) {
            subjectAsked = true
            dialog = ComposeDialog.EmptySubject
            return
        }
        dialog = null
        sealed = true
        preparing = true
        background {
            try {
                val id = checkNotNull(persist(current, ignoreSeal = true)) { "Draft was discarded" }
                when (val result = container.send.enqueue(id)) {
                    is EnqueueResult.Queued -> {
                        draftId = null
                        setOutbox(result.outboxId)
                        observe(result.outboxId)
                        process(result.outboxId) { container.send.processAccount(group.account.id) }
                    }
                    EnqueueResult.NoRecipients -> reopen(R.string.compose_error_no_recipient)
                    is EnqueueResult.InvalidAddress -> reopen(R.string.compose_error_invalid_recipient)
                    EnqueueResult.DraftMissing -> reopen(R.string.compose_unexpected_error)
                }
            } catch (error: Exception) {
                if (outboxId == null) sealed = false
                throw error
            } finally {
                preparing = false
            }
        }
    }

    private fun reopen(@StringRes message: Int) {
        sealed = false
        events.trySend(UiMessage(message))
    }

    private fun setOutbox(id: Long) {
        outboxId = id
        hasOutbox = true
    }

    fun confirmSenderAndSend() {
        dialog = null
        confirmSender()
        senderChecked = true
        send()
    }

    fun retry() {
        val id = outboxId ?: return
        process(id) {
            val row = container.db.outbox().get(id) ?: return@process
            if (row.state == SendState.FAILED) container.send.retry(id) else container.send.processAccount(row.accountId)
        }
    }

    private fun process(id: Long, block: suspend () -> Unit) {
        processing = true
        background {
            try {
                block()
            } finally {
                container.db.outbox().get(id)?.let { outbox = OutboxSnapshot(it.state, it.failure) }
                processing = false
            }
        }
    }

    private fun observe(id: Long) {
        viewModelScope.launch {
            container.send.items
                .map { list -> list.firstOrNull { it.id == id }?.let { OutboxSnapshot(it.state, it.failure) } }
                .distinctUntilChanged()
                .collect { if (it != null) outbox = it }
        }
    }

    private companion object {
        const val AUTOSAVE_DELAY_MILLIS = 1500L
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
}
