package app.dustbird.mail.sync

import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.mime.ComposedMessage
import app.dustbird.mail.core.mime.MimeComposer
import app.dustbird.mail.core.mime.OutgoingMessage
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.RejectKind
import app.dustbird.mail.core.net.SmtpSender
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.core.send.FailureCodes
import app.dustbird.mail.core.send.MessageIds
import app.dustbird.mail.core.send.OutboxItem
import app.dustbird.mail.core.send.OutboxStore
import app.dustbird.mail.core.send.SendEvent
import app.dustbird.mail.core.send.SendMachine
import app.dustbird.mail.core.send.SendOrchestrator
import app.dustbird.mail.core.send.SendRecord
import app.dustbird.mail.core.send.SendRoute
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.OutboxEntity
import app.dustbird.mail.data.repo.AccountRepository
import app.dustbird.mail.data.repo.DraftRepository
import app.dustbird.mail.data.secure.CredentialStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.dustbird.mail.core.mime.MessageParser
import java.util.UUID
import java.io.File

sealed interface EnqueueResult {
    data class Queued(val outboxId: Long) : EnqueueResult
    data object NoRecipients : EnqueueResult
    data class InvalidAddress(val address: String) : EnqueueResult
    data object DraftMissing : EnqueueResult
    data object Unsendable : EnqueueResult
}

class SendService(
    private val db: MailDatabase,
    private val accounts: AccountRepository,
    private val drafts: DraftRepository,
    private val credentials: CredentialStore,
    smtp: SmtpSender,
    imap: ImapConnector,
    private val outboxDir: File,
    private val onQueued: () -> Unit = {},
    private val onSent: suspend (accountId: Long, inReplyTo: String) -> Unit = { _, _ -> },
    pause: (Long) -> Unit = { Thread.sleep(it) },
    private val onHeld: (accountId: Long, sendAfter: Long) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val onFailed: (row: OutboxEntity, watched: Boolean) -> Unit = { _, _ -> },
) {
    val items: Flow<List<OutboxEntity>> = db.outbox().observeAll()
    private val _tracked = MutableStateFlow<Long?>(null)
    val tracked: StateFlow<Long?> = _tracked

    @OptIn(ExperimentalCoroutinesApi::class)
    val trackedItem: Flow<OutboxEntity?> = _tracked.flatMapLatest { id -> if (id == null) flowOf(null) else db.outbox().observe(id) }
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val store = OutboxStore { id, record ->
        db.sync().setOutboxState(id, record.state, record.attempts, record.failure, System.currentTimeMillis())
    }
    private val orchestrator = SendOrchestrator(smtp, imap, store, pause)

    suspend fun enqueue(draftId: Long, sendAfter: Long = 0, track: Boolean = false): EnqueueResult {
        val draft = db.drafts().get(draftId) ?: return EnqueueResult.DraftMissing
        val identity = db.identities().get(draft.identityId) ?: return EnqueueResult.DraftMissing
        val everyone = Codec.parseAddresses(draft.toAddresses) + Codec.parseAddresses(draft.ccAddresses) + Codec.parseAddresses(draft.bccAddresses)
        if (everyone.isEmpty()) return EnqueueResult.NoRecipients
        everyone.firstOrNull { !Addresses.isValid(it.address) }?.let { return EnqueueResult.InvalidAddress(it.address) }
        val now = clock()
        val held = sendAfter > now
        val id = lock(draft.accountId).withLock {
            db.outbox().byDraft(draftId).forEach { db.outbox().delete(it.id) }
            val id = db.outbox().insert(
                OutboxEntity(
                    accountId = draft.accountId,
                    identityId = identity.id,
                    state = SendState.QUEUED,
                    attempts = 0,
                    failure = null,
                    messageId = MessageIds.generate(identity.address),
                    subject = draft.subject,
                    recipientSummary = everyone.joinToString(", ") { it.name ?: it.address },
                    raw = ByteArray(0),
                    rawWithBcc = ByteArray(0),
                    envelopeFrom = identity.address,
                    recipients = everyone.joinToString("\n") { it.address },
                    createdAt = now,
                    updatedAt = now,
                    sendAfter = if (held) sendAfter else 0,
                    draftId = draftId,
                ),
            )
            if (!held && !composes(id)) {
                db.outbox().delete(id)
                return EnqueueResult.Unsendable
            }
            id
        }
        if (track) _tracked.value = id
        if (held) onHeld(draft.accountId, sendAfter) else onQueued()
        return EnqueueResult.Queued(id)
    }

    private suspend fun composes(id: Long): Boolean = try {
        db.outbox().get(id)?.let { release(it) }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun prepare(row: OutboxEntity): Pair<OutboxEntity, OutboxItem>? = try {
        release(row)?.let { ready -> ready to withContext(Dispatchers.IO) { toItem(ready) } }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        store.save(row.id, SendRecord(SendState.FAILED, row.attempts, FailureCodes.COMPOSE))
        onFailed(row, _tracked.value == row.id)
        null
    }

    private suspend fun release(entity: OutboxEntity): OutboxEntity? {
        val draftId = entity.draftId ?: return entity
        val draft = db.drafts().get(draftId)
        val identity = draft?.let { db.identities().get(it.identityId) }
        if (draft == null || identity == null) {
            db.outbox().delete(entity.id)
            untrack(entity.id)
            return null
        }
        val attachments = drafts.outgoingAttachments(draftId)
        val composed = withContext(Dispatchers.IO) {
            MimeComposer.compose(
                OutgoingMessage(
                    from = EmailAddress(identity.address, identity.displayName.ifBlank { null }),
                    to = Codec.parseAddresses(draft.toAddresses),
                    cc = Codec.parseAddresses(draft.ccAddresses),
                    bcc = Codec.parseAddresses(draft.bccAddresses),
                    subject = draft.subject,
                    text = draft.body,
                    messageId = entity.messageId,
                    inReplyTo = draft.inReplyTo,
                    references = Codec.parseStrings(draft.references),
                    sentAt = Instant.ofEpochMilli(clock()),
                    attachments = attachments,
                ),
            )
        }
        val rawFile = withContext(Dispatchers.IO) {
            outboxDir.mkdirs()
            File(outboxDir, "${UUID.randomUUID()}.eml").apply { writeBytes(composed.rawWithBcc) }
        }
        db.outbox().release(entity.id, rawFile.absolutePath, composed.envelopeFrom, composed.recipients.joinToString("\n"), clock())
        drafts.delete(draftId)
        return db.outbox().get(entity.id)
    }

    suspend fun cancelHeld(id: Long): Long? {
        val accountId = db.outbox().get(id)?.accountId ?: return null
        return lock(accountId).withLock {
            val row = db.outbox().get(id)
            val draftId = row?.draftId
            if (row == null || row.state !in setOf(SendState.QUEUED, SendState.FAILED) || draftId == null) return@withLock null
            db.outbox().delete(id)
            if (_tracked.value == id) _tracked.value = null
            draftId
        }
    }

    suspend fun sendNow(id: Long) {
        val row = db.outbox().get(id) ?: return
        db.outbox().setSendAfter(id, 0, clock())
        processAccount(row.accountId)
    }

    suspend fun nextDue(): Long? = db.outbox().nextDue(clock())

    fun untrack(id: Long) {
        _tracked.compareAndSet(id, null)
    }

    suspend fun recoverAfterCrash() {
        db.outbox().sending().forEach {
            val next = SendMachine.next(SendRecord(it.state, it.attempts, it.failure), SendEvent.RecoverAfterCrash)
            store.save(it.id, next)
        }
        db.outbox().accepted().forEach {
            val next = SendMachine.next(SendRecord(it.state, it.attempts, it.failure), SendEvent.SentCopyFailed(FailureCodes.SAVE_ERROR))
            store.save(it.id, next)
        }
    }

    suspend fun tidy() {
        val cutoff = clock() - SAVED_RETENTION_MILLIS
        db.outbox().saved().forEach { row ->
            row.rawPath?.let { File(it).delete() }
            if (row.updatedAt < cutoff) db.outbox().delete(row.id) else if (row.rawPath != null) db.outbox().clearRaw(row.id)
        }
    }

    suspend fun processAll() = db.accounts().getAll().forEach { account ->
        try {
            processAccount(account.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    suspend fun processAccount(accountId: Long) = lock(accountId).withLock {
        due(accountId).forEach { row -> prepare(row)?.let { (ready, item) -> send(ready, item) } }
        if (due(accountId).isNotEmpty()) onQueued()
    }

    suspend fun hasQueued(): Boolean = db.accounts().getAll().any { due(it.id).isNotEmpty() }

    private suspend fun due(accountId: Long): List<OutboxEntity> {
        val now = clock()
        return db.outbox().byAccountAndStates(accountId, listOf(SendState.QUEUED)).filter { it.sendAfter <= now }
    }

    suspend fun retry(id: Long) = transition(id, SendEvent.UserRetry) { processAccount(it.accountId) }

    suspend fun resend(id: Long) = transition(id, SendEvent.UserResend) { processAccount(it.accountId) }

    suspend fun checkUnknown(id: Long) = withItem(id) { entity, account, route ->
        orchestrator.resolveUnknown(toItem(entity), route).also { afterRecord(entity, account, it, outcomeCode = null) }
    }

    suspend fun confirmSent(id: Long) = withItem(id) { entity, account, route ->
        orchestrator.saveAfterConfirmation(toItem(entity), route).also { afterRecord(entity, account, it, outcomeCode = null) }
    }

    suspend fun retrySave(id: Long) = withItem(id) { entity, account, route ->
        orchestrator.retrySave(toItem(entity), route).also { afterRecord(entity, account, it, outcomeCode = null) }
    }

    suspend fun discard(id: Long) {
        val entity = db.outbox().get(id) ?: return
        if (entity.state in setOf(SendState.FAILED, SendState.UNKNOWN, SendState.SAVED, SendState.SAVE_FAILED)) {
            db.outbox().delete(id)
            entity.rawPath?.let { File(it).delete() }
            untrack(id)
        }
    }

    private suspend fun send(entity: OutboxEntity, item: OutboxItem) {
        val account = db.accounts().get(entity.accountId) ?: return
        val route = route(account)
        if (route == null) {
            val began = SendMachine.next(SendRecord(entity.state, entity.attempts, entity.failure), SendEvent.Begin)
            store.save(entity.id, began)
            store.save(entity.id, SendMachine.next(began, SendEvent.SmtpRejected(FailureCodes.rejected(RejectKind.AUTH, null))))
            accounts.markNeedsReauth(account.id)
            onFailed(entity, _tracked.value == entity.id)
            return
        }
        val report = withContext(Dispatchers.IO) { orchestrator.send(item, route) }
        val outcomeCode = report.record.failure
        afterRecord(entity, account, report.record, outcomeCode)
        if (report.record.state in setOf(SendState.FAILED, SendState.UNKNOWN)) onFailed(entity, _tracked.value == entity.id)
        if (report.record.state in setOf(SendState.ACCEPTED, SendState.SAVED, SendState.SAVE_FAILED)) {
            val inReplyTo = withContext(Dispatchers.Default) { MessageParser.inReplyTo(item.message.raw) }
            if (inReplyTo != null) runCatching { onSent(account.id, inReplyTo) }
        }
    }

    private suspend fun afterRecord(entity: OutboxEntity, account: AccountEntity, record: SendRecord, outcomeCode: String?) {
        val sent = record.state in setOf(SendState.ACCEPTED, SendState.SAVED, SendState.SAVE_FAILED)
        if (sent) {
            accounts.setVerification(entity.identityId, SendVerification.VERIFIED)
            accounts.updateStages(account.id) { it.copy(smtpAuth = StageState.OK, send = StageState.OK) }
        }
        if (record.state == SendState.SAVED) accounts.updateStages(account.id) { it.copy(sentCopy = StageState.OK) }
        if (record.state == SendState.SAVE_FAILED && record.failure == FailureCodes.SAVE_NOT_BY_SERVER) {
            accounts.updateStages(account.id) { it.copy(sentCopy = StageState.FAILED) }
        }
        val failure = outcomeCode ?: record.failure
        if (record.state == SendState.FAILED && failure != null) {
            when (failure.substringBefore('|')) {
                RejectKind.SENDER.name -> accounts.setVerification(entity.identityId, SendVerification.FAILED)
                RejectKind.AUTH.name -> {
                    accounts.updateStages(account.id) { it.copy(smtpAuth = StageState.FAILED) }
                    accounts.markNeedsReauth(account.id)
                }
                RejectKind.TLS.name -> accounts.updateStages(account.id) { it.copy(smtpAuth = StageState.FAILED) }
            }
        }
    }

    private suspend fun transition(id: Long, event: SendEvent, then: suspend (OutboxEntity) -> Unit) {
        val entity = db.outbox().get(id) ?: return
        store.save(id, SendMachine.next(SendRecord(entity.state, entity.attempts, entity.failure), event))
        then(entity)
    }

    private suspend fun withItem(id: Long, block: suspend (OutboxEntity, AccountEntity, SendRoute) -> Unit) {
        val entity = db.outbox().get(id) ?: return
        val account = db.accounts().get(entity.accountId) ?: return
        val route = route(account) ?: return
        lock(account.id).withLock { withContext(Dispatchers.IO) { block(entity, account, route) } }
    }

    private suspend fun route(account: AccountEntity): SendRoute? {
        val secret = credentials.get(account.id) ?: return null
        val profile = Providers.byId(account.providerId)
        val sent = db.folders().byRole(account.id, FolderRole.SENT)
        return SendRoute(
            smtp = ServerEndpoint(account.smtpHost, account.smtpPort, account.smtpSecurity),
            imap = ServerEndpoint(account.imapHost, account.imapPort, account.imapSecurity),
            credentials = Credentials(account.username, secret),
            sentFolderPath = sent?.path,
            serverSavesSentCopy = profile.serverSavesSentCopy,
        )
    }

    private fun toItem(entity: OutboxEntity): OutboxItem {
        val withBcc = entity.rawPath?.let { File(it).readBytes() } ?: entity.rawWithBcc
        val raw = if (entity.rawPath == null) entity.raw else MimeComposer.withoutBcc(withBcc)
        return OutboxItem(
            id = entity.id,
            record = SendRecord(entity.state, entity.attempts, entity.failure),
            message = ComposedMessage(raw, withBcc, entity.envelopeFrom, entity.recipients.split("\n").filter { it.isNotEmpty() }),
            messageId = entity.messageId,
        )
    }

    private fun lock(accountId: Long) = locks.getOrPut(accountId) { Mutex() }
}

private const val SAVED_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
