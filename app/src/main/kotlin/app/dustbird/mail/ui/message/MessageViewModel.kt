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

sealed interface HeaderState {
    data object Loading : HeaderState
    data object Missing : HeaderState
    data class Found(
        val detail: MessageDetail,
        val account: MailAccount?,
        val accountIndex: Int,
        val showAccount: Boolean,
        val receivedIdentities: List<MailIdentity>,
    ) : HeaderState
}

sealed interface BodyState {
    data object Loading : BodyState
    data class Failed(val failure: ConnectFailure?) : BodyState
    data class Ready(
        val html: String?,
        val text: String?,
        val blockedImages: Int,
        val imagesAllowed: Boolean,
        val attachments: List<AttachmentInfo>,
    ) : BodyState
}

class MessageViewModel(private val container: AppContainer, private val id: Long) : ViewModel() {
    private val loaded = MutableStateFlow<BodyResult?>(null)
    private val replyAllUseful = MutableStateFlow(false)
    private val allowImages = container.mail.detail(id).map { it?.allowRemoteImages }.distinctUntilChanged()

    val replyAll: StateFlow<Boolean> = replyAllUseful

    val header: StateFlow<HeaderState> = combine(container.mail.detail(id), container.accounts.accounts, container.accounts.identities) { detail, accounts, identities ->
        if (detail == null) {
            HeaderState.Missing
        } else {
            val accountId = detail.summary.accountId
            val matched = (detail.summary.match as? RecipientMatch.Matched)?.identityIds.orEmpty()
            HeaderState.Found(
                detail = detail,
                account = accounts.firstOrNull { it.id == accountId },
                accountIndex = accounts.indexOfFirst { it.id == accountId }.coerceAtLeast(0),
                showAccount = accounts.size > 1,
                receivedIdentities = identities.filter { it.id in matched }.sortedBy { it.sortOrder },
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
            imagesAllowed = allowImages,
            attachments = result.attachments,
        )
    }
}
