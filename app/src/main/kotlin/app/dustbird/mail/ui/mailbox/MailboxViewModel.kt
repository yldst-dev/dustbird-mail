package app.dustbird.mail.ui.mailbox

import androidx.compose.ui.semantics.role
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.data.repo.MoveTicket
import app.dustbird.mail.data.repo.MoveTarget
import app.dustbird.mail.data.repo.MessageFilter

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

private enum class Refresh { IDLE, QUIET, PULLED }

private class Status(
    val sync: Map<Long, AccountSyncState>,
    val refreshing: Refresh,
    val loadingOlder: Boolean,
    val exhausted: Set<FolderKey>,
    val limit: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MailboxViewModel(private val container: AppContainer) : ViewModel() {
    private val scope = MutableStateFlow<MailboxScope>(MailboxScope.Unified)
    private val limit = MutableStateFlow(PAGE_SIZE)
    private val refreshing = MutableStateFlow(Refresh.IDLE)
    private val loadingOlder = MutableStateFlow(false)
    private val exhausted = MutableStateFlow<Set<FolderKey>>(emptySet())
    private val selectedId = MutableStateFlow<Long?>(null)
    private val filter = MutableStateFlow(MessageFilter.ALL)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val _events = Channel<MailboxEvent>(Channel.BUFFERED)
    val events: Flow<MailboxEvent> = _events.receiveAsFlow().filter { it !is MailboxEvent.Moved || container.actions.isPending(it.ticket) }
    private val _confirmDelete = MutableStateFlow<List<Long>?>(null)
    val confirmDelete: StateFlow<List<Long>?> = _confirmDelete

    private val messages: Flow<List<MessageSummary>?> = scope.flatMapLatest { target ->
        flow {
            emit(null)
            emitAll(combine(limit, filter, ::Pair).flatMapLatest { (size, chosen) -> container.mail.messages(target, size, chosen) })
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

    private val extras = combine(filter, selection, container.mail.folders()) { chosen, picked, folders -> Triple(chosen, picked, folders) }

    val state: StateFlow<MailboxUiState?> = combine(content, status, outboxPending, selectedId, extras) { content, status, pending, selected, extra ->
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
            busy = status.refreshing != Refresh.IDLE || scopeAccounts.any { status.sync[it.id] == AccountSyncState.Syncing },
            refreshing = status.refreshing == Refresh.PULLED,
            loadingOlder = status.loadingOlder,
            hasMore = content.messages != null && (content.messages.size >= status.limit || targets.any { it !in status.exhausted }),
            outboxPending = pending,
            selectedId = selected,
            filter = extra.first,
            folders = extra.third,
            selection = extra.second.intersect(content.messages.orEmpty().map { it.id }.toSet()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val collapsedAccounts: StateFlow<Set<Long>> = container.settings.collapsedAccounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleAccount(accountId: Long) {
        viewModelScope.launch { container.settings.toggleCollapsedAccount(accountId) }
    }

    init {
        start(Refresh.QUIET)
        viewModelScope.launch {
            container.actions.started.collect { started ->
                val ticket = started.ticket
                if (ticket.skipped > 0) _events.trySend(MailboxEvent.Notice(R.string.mailbox_move_unavailable))
                if (ticket.count > 0) {
                    val text = when {
                        started.permanent -> R.string.mailbox_moved_deleted
                        started.target == MoveTarget.Role(FolderRole.TRASH) -> R.string.mailbox_moved_trash
                        started.target == MoveTarget.Role(FolderRole.ARCHIVE) -> R.string.mailbox_moved_archive
                        started.target == MoveTarget.Role(FolderRole.SPAM) -> R.string.mailbox_moved_spam
                        else -> R.string.mailbox_moved_folder
                    }
                    _events.trySend(MailboxEvent.Moved(ticket, text))
                }
            }
        }
        viewModelScope.launch {
            container.actions.failures.collect { _events.trySend(MailboxEvent.Notice(R.string.mailbox_move_failed, listOf(it))) }
        }
    }

    fun setFilter(value: MessageFilter) {
        filter.value = value
        limit.value = PAGE_SIZE
        selection.value = emptySet()
    }

    val draftCount: StateFlow<Int> = container.drafts.drafts.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    suspend fun draftFor(messageId: Long): Long? {
        val message = container.db.messages().get(messageId) ?: return null
        val header = message.messageId ?: return null
        return container.db.drafts().byMessageId(message.accountId, header)?.id?.takeIf { container.db.outbox().byDraft(it).isEmpty() }
    }

    fun toggleSelection(id: Long) = selection.update { if (id in it) it - id else it + id }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun selectAll() {
        selection.value = state.value?.messages.orEmpty().map { it.id }.toSet()
    }

    fun move(ids: Collection<Long>, target: MoveTarget) {
        val list = ids.toList()
        if (list.isEmpty()) return
        selection.update { it - list.toSet() }
        if (selectedId.value in list) selectedId.value = null
        container.scope.launch { container.actions.start(list, target) }
    }

    fun delete(ids: Collection<Long>) {
        val list = ids.toList()
        if (list.isEmpty()) return
        viewModelScope.launch {
            if (container.actions.deletesPermanently(list)) _confirmDelete.value = list else move(list, MoveTarget.Role(FolderRole.TRASH))
        }
    }

    fun answerDeleteConfirm(confirmed: Boolean) {
        val ids = _confirmDelete.value
        _confirmDelete.value = null
        if (confirmed && ids != null) move(ids, MoveTarget.Role(FolderRole.TRASH))
    }

    fun undo(ticket: MoveTicket) = container.actions.undo(ticket)

    fun commit(ticket: MoveTicket) = container.actions.commitNow(ticket)

    fun setSeen(ids: Collection<Long>, seen: Boolean) {
        val list = ids.toList()
        if (list.isEmpty()) return
        selection.value = emptySet()
        container.scope.launch { container.mail.setSeen(list, seen) }
    }

    fun setFlagged(ids: Collection<Long>, flagged: Boolean) {
        val list = ids.toList()
        if (list.isEmpty()) return
        selection.value = emptySet()
        container.scope.launch {
            if (!container.mail.setFlagged(list, flagged)) _events.trySend(MailboxEvent.Notice(R.string.mailbox_star_failed))
        }
    }
    fun select(id: Long?) {
        selectedId.value = id
    }

    fun openScope(target: MailboxScope) {
        if (scope.value == target) return
        scope.value = target
        limit.value = PAGE_SIZE
        selectedId.value = null
        selection.value = emptySet()
        if (target is MailboxScope.Folder && !start(Refresh.QUIET)) container.scope.launch { container.sync.syncFolder(target.folderId) }
    }

    fun refresh(): Boolean = start(Refresh.PULLED)

    private fun start(mode: Refresh): Boolean {
        if (!refreshing.compareAndSet(Refresh.IDLE, mode)) {
            if (mode == Refresh.PULLED) refreshing.compareAndSet(Refresh.QUIET, Refresh.PULLED)
            return false
        }
        val target = scope.value
        container.scope.launch {
            try {
                when (target) {
                    MailboxScope.Unified -> container.sync.syncAll()
                    is MailboxScope.Account -> container.sync.syncAccount(target.accountId)
                    is MailboxScope.Folder -> container.sync.syncFolder(target.folderId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            } finally {
                exhausted.value = emptySet()
                refreshing.value = Refresh.IDLE
                container.digests.refresh()
            }
        }
        return true
    }

    fun loadOlder() {
        if (!loadingOlder.compareAndSet(false, true)) return
        limit.update { it + PAGE_SIZE }
        val target = scope.value
        container.scope.launch {
            try {
                val pending = olderTargetsOf(target, container.accounts.accounts.first()).filter { it !in exhausted.value }
                val results = coroutineScope {
                    pending.map { key ->
                        async {
                            key to attempt {
                                val folderId = key.folderId
                                val role = key.role
                                when {
                                    folderId != null -> container.sync.loadOlderFolder(folderId)
                                    role != null -> container.sync.loadOlder(key.accountId, role)
                                    else -> 0
                                }
                            }
                        }
                    }.awaitAll()
                }
                exhausted.update { done -> done + results.filter { it.second == 0 }.map { it.first } }
                if (results.any { it.second == null }) _events.trySend(MailboxEvent.Notice(R.string.mailbox_older_failed))
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
