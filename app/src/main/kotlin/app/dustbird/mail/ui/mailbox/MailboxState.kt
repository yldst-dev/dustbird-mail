package app.dustbird.mail.ui.mailbox

import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.data.repo.MessageSummary
import app.dustbird.mail.sync.AccountSyncState

data class FolderKey(val accountId: Long, val role: FolderRole)

data class AccountProblem(val account: MailAccount, val failure: ConnectFailure?)

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
)

enum class MailboxBody { LOADING, NO_ACCOUNTS, OFFLINE, FAILED, EMPTY, MESSAGES }

fun scopeAccountsOf(scope: MailboxScope, accounts: List<MailAccount>): List<MailAccount> = when (scope) {
    MailboxScope.Unified -> accounts
    is MailboxScope.Account -> accounts.filter { it.id == scope.accountId }
}

fun olderTargetsOf(scope: MailboxScope, accounts: List<MailAccount>): List<FolderKey> = when (scope) {
    MailboxScope.Unified -> accounts.map { FolderKey(it.id, FolderRole.INBOX) }
    is MailboxScope.Account -> listOf(FolderKey(scope.accountId, scope.role))
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
        busy -> MailboxBody.LOADING
        !online -> MailboxBody.OFFLINE
        problems.size == scopeAccounts.size && problems.isNotEmpty() -> MailboxBody.FAILED
        else -> MailboxBody.EMPTY
    }
}

fun MailboxUiState.unreadInScope(): Int? = when (val target = scope) {
    MailboxScope.Unified -> unread.values.sum()
    is MailboxScope.Account -> if (target.role == FolderRole.INBOX) unread[target.accountId] ?: 0 else null
}
