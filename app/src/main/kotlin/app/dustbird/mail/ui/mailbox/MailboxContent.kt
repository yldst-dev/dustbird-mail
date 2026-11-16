package app.dustbird.mail.ui.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.data.repo.MailboxScope
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxPane(
    state: MailboxUiState,
    online: Boolean,
    listState: LazyListState,
    selectable: Boolean,
    onOpenMessage: (Long) -> Unit,
    onRefresh: () -> Unit,
    onLoadOlder: () -> Unit,
    onOpenAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body = state.body(online)
    val formatter = rememberMessageTimeFormatter()
    val now = remember(state.messages) { Instant.now() }
    val indexed = remember(state.accounts) { state.accounts.withIndex().associateBy { it.value.id } }
    val showAccounts = state.accounts.size > 1 && state.scope == MailboxScope.Unified

    Column(modifier) {
        if (!online) OfflineNotice()
        val visibleProblems = if (online) state.problems else state.problems.filterNot { it.failure == ConnectFailure.UNREACHABLE }
        if (visibleProblems.isNotEmpty()) {
            ProblemBanner(
                problems = visibleProblems,
                allFailed = state.problems.size == state.scopeAccounts.size,
                accounts = state.accounts,
                onRetry = onRefresh,
                onOpenAccounts = onOpenAccounts,
            )
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                if (body == MailboxBody.MESSAGES) {
                    items(state.messages.orEmpty(), key = { it.id }) { message ->
                        val account = indexed[message.accountId]
                        Column {
                            MessageRow(
                                message = message,
                                time = formatter.format(message.sortDate, now),
                                accountLabel = if (showAccounts) account?.value?.label else null,
                                accountIndex = account?.index ?: 0,
                                identityLabels = state.identityLabels,
                                selected = if (selectable) message.id == state.selectedId else null,
                                onClick = { onOpenMessage(message.id) },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                    item(key = "older") { OlderAction(state, onLoadOlder) }
                } else {
                    item(key = "state") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            StateMessage(body, onRefresh, onOpenAccounts)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OlderAction(state: MailboxUiState, onLoadOlder: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.Center) {
        when {
            state.loadingOlder -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadingIndicator()
                Text(stringResource(R.string.mailbox_older_loading), style = MaterialTheme.typography.bodyMedium)
            }
            state.hasMore -> TextButton(onClick = onLoadOlder) { Text(stringResource(R.string.mailbox_older_action)) }
            else -> Text(
                text = stringResource(R.string.mailbox_older_end),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StateMessage(body: MailboxBody, onRetry: () -> Unit, onOpenAccounts: () -> Unit) {
    if (body == MailboxBody.LOADING) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator()
            Text(stringResource(R.string.mailbox_loading), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val title = when (body) {
        MailboxBody.NO_ACCOUNTS -> R.string.mailbox_no_accounts_title
        MailboxBody.OFFLINE -> R.string.mailbox_offline_title
        MailboxBody.FAILED -> R.string.mailbox_failed_title
        else -> R.string.mailbox_empty_title
    }
    val text = when (body) {
        MailboxBody.NO_ACCOUNTS -> R.string.mailbox_no_accounts_body
        MailboxBody.OFFLINE -> R.string.mailbox_offline_body
        MailboxBody.FAILED -> R.string.mailbox_failed_body
        else -> R.string.mailbox_empty_body
    }
    Column(
        Modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        when (body) {
            MailboxBody.NO_ACCOUNTS -> FilledTonalButton(onClick = onOpenAccounts) { Text(stringResource(R.string.mailbox_open_accounts)) }
            MailboxBody.FAILED -> FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.mailbox_retry)) }
            else -> Unit
        }
    }
}

@Composable
private fun OfflineNotice() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.mailbox_offline_notice),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProblemBanner(
    problems: List<AccountProblem>,
    allFailed: Boolean,
    accounts: List<app.dustbird.mail.core.model.MailAccount>,
    onRetry: () -> Unit,
    onOpenAccounts: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(if (allFailed) R.string.mailbox_problem_title_all else R.string.mailbox_problem_title_some),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            problems.forEach { problem ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (accounts.size > 1) AccountLabel(problem.account.label, accounts.indexOfFirst { it.id == problem.account.id }.coerceAtLeast(0))
                    Text(stringResource(problemRes(problem.failure)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.mailbox_retry), fontWeight = FontWeight.SemiBold) }
                if (problems.any { it.failure == null }) {
                    TextButton(onClick = onOpenAccounts) { Text(stringResource(R.string.mailbox_open_accounts), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

private fun problemRes(failure: ConnectFailure?): Int = when (failure) {
    null -> R.string.mailbox_problem_reauth
    ConnectFailure.AUTH -> R.string.mailbox_problem_auth
    ConnectFailure.UNREACHABLE -> R.string.mailbox_problem_unreachable
    ConnectFailure.TLS -> R.string.mailbox_problem_tls
    ConnectFailure.SERVICE_DISABLED -> R.string.mailbox_problem_disabled
    ConnectFailure.OTHER -> R.string.mailbox_problem_other
}
