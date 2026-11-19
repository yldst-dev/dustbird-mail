package app.dustbird.mail.ui.mailbox

import androidx.annotation.StringRes
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.data.repo.MessageFilter
import app.dustbird.mail.data.repo.MessageSummary
import app.dustbird.mail.data.repo.MoveTicket
import app.dustbird.mail.sync.AccountSyncState

data class FolderKey(val accountId: Long, val role: FolderRole?, val folderId: Long? = null)

data class AccountProblem(val account: MailAccount, val failure: ConnectFailure?)

sealed interface MailboxEvent {
    data class Notice(@StringRes val text: Int, val args: List<Any> = emptyList()) : MailboxEvent
    data class Moved(val ticket: MoveTicket, @StringRes val text: Int) : MailboxEvent
}

data class MailboxUiState(
    val scope: MailboxScope,
    val accounts: List<MailAccount>,
    val scopeAccounts: List<MailAccount>,
    val identityLabels: Map<Long, String>,
    val messages: List<MessageSummary>?,
    val unread: Map<Long, Int>,
    val problems: List<AccountProblem>,
    val busy: Boolean,
    val refreshing: Boolean,
    val loadingOlder: Boolean,
    val hasMore: Boolean,
    val outboxPending: Int,
    val selectedId: Long?,
    val filter: MessageFilter = MessageFilter.ALL,
    val folders: List<FolderEntity> = emptyList(),
    val selection: Set<Long> = emptySet(),
)

enum class MailboxBody { LOADING, NO_ACCOUNTS, OFFLINE, FAILED, EMPTY, MESSAGES }

fun scopeAccountsOf(scope: MailboxScope, accounts: List<MailAccount>): List<MailAccount> = when (scope) {
    MailboxScope.Unified -> accounts
    is MailboxScope.Account -> accounts.filter { it.id == scope.accountId }
    is MailboxScope.Folder -> accounts.filter { it.id == scope.accountId }
}

fun olderTargetsOf(scope: MailboxScope, accounts: List<MailAccount>): List<FolderKey> = when (scope) {
    MailboxScope.Unified -> accounts.map { FolderKey(it.id, FolderRole.INBOX) }
    is MailboxScope.Account -> listOf(FolderKey(scope.accountId, scope.role))
    is MailboxScope.Folder -> listOf(FolderKey(scope.accountId, null, scope.folderId))
}

fun problemsOf(accounts: List<MailAccount>, states: Map<Long, AccountSyncState>): List<AccountProblem> = accounts.mapNotNull { account ->
    when (val state = states[account.id]) {
        is AccountSyncState.Error -> AccountProblem(account, state.failure)
        AccountSyncState.NeedsReauth -> AccountProblem(account, null)
        else -> if (account.needsReauth) AccountProblem(account, null) else null
    }
}

fun MailboxUiState.body(online: Boolean): MailboxBody {
    val rows = messages
    return when {
        rows == null -> MailboxBody.LOADING
        rows.isNotEmpty() -> MailboxBody.MESSAGES
        accounts.isEmpty() -> MailboxBody.NO_ACCOUNTS
        busy && !refreshing -> MailboxBody.LOADING
        filter != MessageFilter.ALL -> MailboxBody.EMPTY
        !online -> MailboxBody.OFFLINE
        problems.size == scopeAccounts.size && problems.isNotEmpty() -> MailboxBody.FAILED
        else -> MailboxBody.EMPTY
    }
}

fun MailboxUiState.unreadInScope(): Int? = when (val target = scope) {
    MailboxScope.Unified -> unread.values.sum()
    is MailboxScope.Account -> if (target.role == FolderRole.INBOX) unread[target.accountId] ?: 0 else null
    is MailboxScope.Folder -> null
}

fun MailboxUiState.scopeRole(): FolderRole? = when (val target = scope) {
    MailboxScope.Unified -> FolderRole.INBOX
    is MailboxScope.Account -> target.role
    is MailboxScope.Folder -> folders.firstOrNull { it.id == target.folderId }?.role
}

fun FolderEntity.displayName(): String = delimiter?.takeIf { it.isNotEmpty() }?.let { path.split(it).joinToString(" / ") } ?: path
