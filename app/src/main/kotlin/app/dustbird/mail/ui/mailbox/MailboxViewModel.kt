package app.dustbird.mail.ui.mailbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.data.repo.MessageSummary
import app.dustbird.mail.sync.AccountSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 100

private val unsettledSendStates = setOf(
    SendState.QUEUED, SendState.SENDING, SendState.UNKNOWN, SendState.ACCEPTED, SendState.SAVE_FAILED, SendState.FAILED,
)

private class Content(
    val scope: MailboxScope,
    val messages: List<MessageSummary>?,
    val accounts: List<MailAccount>,
    val identities: List<MailIdentity>,
    val unread: Map<Long, Int>,
)

private class Status(
    val sync: Map<Long, AccountSyncState>,
    val refreshing: Boolean,
    val loadingOlder: Boolean,
    val exhausted: Set<FolderKey>,
    val limit: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MailboxViewModel(private val container: AppContainer) : ViewModel() {
    private val scope = MutableStateFlow<MailboxScope>(MailboxScope.Unified)
    private val limit = MutableStateFlow(PAGE_SIZE)
    private val refreshing = MutableStateFlow(false)
    private val loadingOlder = MutableStateFlow(false)
    private val exhausted = MutableStateFlow<Set<FolderKey>>(emptySet())
    private val selectedId = MutableStateFlow<Long?>(null)
    private val _notices = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    val notices: SharedFlow<Int> = _notices

    private val messages: Flow<List<MessageSummary>?> = scope.flatMapLatest { target ->
        flow {
            emit(null)
            emitAll(limit.flatMapLatest { container.mail.messages(target, it) })
        }
    }

    private val content = combine(
        scope,
        messages,
        container.accounts.accounts,
        container.accounts.identities,
        container.mail.unreadCounts(),
        ::Content,
    )

    private val status = combine(container.sync.state, refreshing, loadingOlder, exhausted, limit, ::Status)

    private val outboxPending = container.send.items.map { items -> items.count { it.state in unsettledSendStates } }.distinctUntilChanged()

    val state: StateFlow<MailboxUiState?> = combine(content, status, outboxPending, selectedId) { content, status, pending, selected ->
        val scopeAccounts = scopeAccountsOf(content.scope, content.accounts)
        val targets = olderTargetsOf(content.scope, content.accounts)
        MailboxUiState(
            scope = content.scope,
            accounts = content.accounts,
            scopeAccounts = scopeAccounts,
            identityLabels = content.identities.associate { it.id to it.label },
            messages = content.messages,
            unread = content.unread,
            problems = problemsOf(scopeAccounts, status.sync),
            busy = status.refreshing || scopeAccounts.any { status.sync[it.id] == AccountSyncState.Syncing },
            refreshing = status.refreshing,
            loadingOlder = status.loadingOlder,
            hasMore = content.messages != null && (content.messages.size >= status.limit || targets.any { it !in status.exhausted }),
            outboxPending = pending,
            selectedId = selected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        refresh()
    }

    fun select(id: Long?) {
        selectedId.value = id
    }

    fun openScope(target: MailboxScope) {
        if (scope.value == target) return
        scope.value = target
        limit.value = PAGE_SIZE
        selectedId.value = null
    }

    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        val target = scope.value
        container.scope.launch {
            try {
                when (target) {
                    MailboxScope.Unified -> container.sync.syncAll()
                    is MailboxScope.Account -> container.sync.syncAccount(target.accountId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            } finally {
                refreshing.value = false
            }
        }
    }

    fun loadOlder() {
        if (!loadingOlder.compareAndSet(false, true)) return
        limit.update { it + PAGE_SIZE }
        val target = scope.value
        container.scope.launch {
            try {
                val pending = olderTargetsOf(target, container.accounts.accounts.first()).filter { it !in exhausted.value }
                val results = coroutineScope {
                    pending.map { key -> async { key to attempt { container.sync.loadOlder(key.accountId, key.role) } } }.awaitAll()
                }
                exhausted.update { done -> done + results.filter { it.second == 0 }.map { it.first } }
                if (results.any { it.second == null }) _notices.tryEmit(R.string.mailbox_older_failed)
            } finally {
                loadingOlder.value = false
            }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
