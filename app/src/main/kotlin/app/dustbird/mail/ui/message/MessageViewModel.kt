package app.dustbird.mail.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dustbird.mail.AppContainer
import app.dustbird.mail.core.html.HtmlSanitizer
import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.identity.DraftSeed
import app.dustbird.mail.core.identity.RecipientMatch
import app.dustbird.mail.core.mime.AttachmentInfo
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.data.repo.BodyResult
import app.dustbird.mail.data.repo.MessageDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import java.io.File
import app.dustbird.mail.data.repo.MoveTarget
import app.dustbird.mail.data.repo.AttachmentResult
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.R
import androidx.annotation.StringRes
import android.net.Uri
import android.content.ContentResolver

sealed interface HeaderState {
    data object Loading : HeaderState
    data object Missing : HeaderState
    data class Found(
        val detail: MessageDetail,
        val account: MailAccount?,
        val accountIndex: Int,
        val showAccount: Boolean,
        val receivedIdentities: List<MailIdentity>,
        val receivedAddress: String? = null,
    ) : HeaderState
}

sealed interface BodyState {
    data object Loading : BodyState
    data class Failed(val failure: ConnectFailure?) : BodyState
    data class Ready(
        val html: String?,
        val text: String?,
        val blockedImages: Int,
        val unshownImages: Int,
        val imagesAllowed: Boolean,
        val attachments: List<AttachmentInfo>,
    ) : BodyState
}

sealed interface MessageEvent {
    data class Notice(@StringRes val text: Int) : MessageEvent
    data object Gone : MessageEvent
    data class Open(val file: File, val info: AttachmentInfo) : MessageEvent
}

class MessageViewModel(private val container: AppContainer, private val id: Long) : ViewModel() {
    val events = Channel<MessageEvent>(Channel.BUFFERED)
    private val _downloading = MutableStateFlow<Set<Int>>(emptySet())
    val downloading: StateFlow<Set<Int>> = _downloading
    private val _confirmDelete = MutableStateFlow(false)
    val confirmDelete: StateFlow<Boolean> = _confirmDelete
    val folders: StateFlow<List<FolderEntity>> = combine(container.mail.detail(id), container.mail.folders()) { detail, all ->
        all.filter { it.accountId == detail?.summary?.accountId && it.id != detail.summary.folderId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val loaded = MutableStateFlow<BodyResult?>(null)
    private val replyAllUseful = MutableStateFlow(false)
    private val allowImages = container.mail.detail(id).map { it?.allowRemoteImages }.distinctUntilChanged()

    val replyAll: StateFlow<Boolean> = replyAllUseful

    val header: StateFlow<HeaderState> = combine(container.mail.detail(id), container.accounts.accounts, container.accounts.identities) { detail, accounts, identities ->
        if (detail == null) {
            HeaderState.Missing
        } else {
            val accountId = detail.summary.accountId
            val match = detail.summary.match
            val matched = (match as? RecipientMatch.Matched)?.identityIds.orEmpty()
            HeaderState.Found(
                detail = detail,
                account = accounts.firstOrNull { it.id == accountId },
                accountIndex = accounts.indexOfFirst { it.id == accountId }.coerceAtLeast(0),
                showAccount = accounts.size > 1,
                receivedIdentities = identities.filter { it.id in matched }.sortedBy { it.sortOrder },
                receivedAddress = (match as? RecipientMatch.OwnAddress)?.address,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeaderState.Loading)

    val body: StateFlow<BodyState> = combine(loaded, allowImages, ::render)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyState.Loading)

    init {
        load()
    }

    fun load() {
        loaded.value = null
        viewModelScope.launch {
            val result = container.mail.body(id)
            loaded.value = result
            if (result is BodyResult.Loaded) replyAllUseful.value = withContext(Dispatchers.Default) { replyAllDiffersFromReply() }
        }
    }

    fun setSeen(seen: Boolean) {
        container.scope.launch { container.mail.setSeen(id, seen) }
    }

    fun allowImages() {
        container.scope.launch { container.mail.allowImages(id) }
    }

    fun setFlagged(flagged: Boolean) {
        container.scope.launch {
            if (!container.mail.setFlagged(listOf(id), flagged)) events.trySend(MessageEvent.Notice(R.string.mailbox_star_failed))
        }
    }

    fun move(target: MoveTarget) {
        container.scope.launch {
            val ticket = container.actions.start(listOf(id), target, announceSkipped = false)
            when {
                ticket == null -> Unit
                ticket.count > 0 -> events.trySend(MessageEvent.Gone)
                else -> events.trySend(MessageEvent.Notice(R.string.mailbox_move_unavailable))
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            if (container.actions.deletesPermanently(listOf(id))) _confirmDelete.value = true else move(MoveTarget.Role(FolderRole.TRASH))
        }
    }

    fun answerDeleteConfirm(confirmed: Boolean) {
        _confirmDelete.value = false
        if (confirmed) move(MoveTarget.Role(FolderRole.TRASH))
    }

    fun openAttachment(index: Int) = fetch(index) { file, info -> events.trySend(MessageEvent.Open(file, info)) }

    fun saveAttachment(index: Int, target: Uri, resolver: ContentResolver) = fetch(index) { file, _ ->
        val saved = withContext(Dispatchers.IO) {
            runCatching { resolver.openOutputStream(target)?.use { output -> file.inputStream().use { it.copyTo(output) } } != null }.getOrDefault(false)
        }
        events.trySend(MessageEvent.Notice(if (saved) R.string.message_attachment_saved else R.string.message_attachment_failed))
    }

    private fun fetch(index: Int, then: suspend (File, AttachmentInfo) -> Unit) {
        if (index in _downloading.value) return
        _downloading.update { it + index }
        viewModelScope.launch {
            try {
                when (val result = container.mail.attachment(id, index)) {
                    is AttachmentResult.Ready -> then(result.file, result.info)
                    is AttachmentResult.Failed -> events.trySend(MessageEvent.Notice(R.string.message_attachment_failed))
                }
            } finally {
                _downloading.update { it - index }
            }
        }
    }

    private suspend fun replyAllDiffersFromReply(): Boolean = try {
        val reply = container.mail.replySeed(id, false, "")
        val all = container.mail.replySeed(id, true, "")
        reply != null && all != null && recipients(reply) != recipients(all)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun recipients(seed: DraftSeed): Set<String> = (seed.to + seed.cc).map { Addresses.normalize(it.address) }.toSet()

    private fun render(result: BodyResult?, allowImages: Boolean?): BodyState {
        if (result == null || allowImages == null) return BodyState.Loading
        if (result is BodyResult.Failed) return BodyState.Failed(result.failure)
        result as BodyResult.Loaded
        val sanitized = result.html?.let { HtmlSanitizer.sanitize(it, allowImages) }
        return BodyState.Ready(
            html = sanitized?.body?.takeIf { it.isNotBlank() },
            text = result.text?.takeIf { it.isNotBlank() },
            blockedImages = sanitized?.blockedRemoteImages ?: 0,
            unshownImages = sanitized?.unshownImages ?: 0,
            imagesAllowed = allowImages,
            attachments = result.attachments,
        )
    }
}
