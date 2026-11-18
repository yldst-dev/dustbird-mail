package app.dustbird.mail.ui.mailbox

import app.dustbird.mail.ui.theme.rememberHaptics
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.capsuleShape
import java.time.Instant
import kotlinx.coroutines.delay
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.data.repo.MessageFilter
import app.dustbird.mail.core.model.FolderRole
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.SideEffect

class MailboxRowActions(
    val onOpen: (Long) -> Unit,
    val onToggle: (Long) -> Unit,
    val onArchive: (Long) -> Unit,
    val onDelete: (Long) -> Unit,
    val onFilter: (MessageFilter) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MailboxPane(
    state: MailboxUiState,
    online: Boolean,
    listState: LazyListState,
    selectable: Boolean,
    actions: MailboxRowActions,
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
    val selecting = state.selection.isNotEmpty()
    val haptics = rememberHaptics()
    val role = state.scopeRole()
    val swipeArchive = !selecting && role == FolderRole.INBOX
    val swipeDelete = !selecting && role != FolderRole.TRASH
    val animations = rememberAnimationsEnabled()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()

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
        if (state.accounts.isNotEmpty()) FilterRow(state.filter, actions.onFilter)
        val refreshState = rememberPullToRefreshState()
        val refreshing by rememberUpdatedState(state.refreshing)
        LaunchedEffect(refreshState) {
            snapshotFlow { refreshState.distanceFraction >= 1f }
                .distinctUntilChanged()
                .collect { past -> if (past && !refreshing) haptics.threshold() }
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
            state = refreshState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = refreshState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    elevation = 0.dp,
                )
            },
        ) {
            val firstId = state.messages?.firstOrNull()?.id
            val shownFirst = remember { arrayOf(firstId) }
            SideEffect {
                if (shownFirst[0] != firstId) {
                    shownFirst[0] = firstId
                    if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) listState.requestScrollToItem(0)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(CapsuleGap),
            ) {
                if (body == MailboxBody.MESSAGES) {
                    items(state.messages.orEmpty(), key = { it.id }) { message ->
                        val account = indexed[message.accountId]
                        SwipeRow(
                            archive = swipeArchive,
                            delete = swipeDelete,
                            onArchive = { actions.onArchive(message.id) },
                            onDelete = { actions.onDelete(message.id) },
                            modifier = if (animations) Modifier.animateItem(effects, spatial, effects) else Modifier,
                        ) {
                            MessageRow(
                                message = message,
                                time = formatter.format(message.sortDate, now),
                                accountLabel = if (showAccounts) account?.value?.label else null,
                                accountIndex = account?.index ?: 0,
                                identityLabels = state.identityLabels,
                                selected = if (selectable) message.id == state.selectedId else null,
                                onClick = {
                                    if (selecting) {
                                        haptics.tick()
                                        actions.onToggle(message.id)
                                    } else {
                                        actions.onOpen(message.id)
                                    }
                                },
                                checked = message.id in state.selection,
                                onLongClick = { actions.onToggle(message.id) },
                            )
                        }
                    }
                    item(key = "older") {
                        OlderAction(state, onLoadOlder, if (animations) Modifier.animateItem(effects, spatial, effects) else Modifier)
                    }
                } else {
                    item(key = "state") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                StateMessage(body, onRefresh, onOpenAccounts)
                                if (body == MailboxBody.EMPTY && state.filter == MessageFilter.ALL && (state.hasMore || state.loadingOlder)) {
                                    OlderAction(state, onLoadOlder)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(filter: MessageFilter, onFilter: (MessageFilter) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            MessageFilter.ALL to R.string.mailbox_filter_all,
            MessageFilter.UNREAD to R.string.mailbox_filter_unread,
            MessageFilter.FLAGGED to R.string.mailbox_filter_starred,
        ).forEach { (value, label) ->
            FilterChip(selected = filter == value, onClick = { haptics.tick(); onFilter(value) }, label = { Text(stringResource(label)) }, shape = Pill)
        }
    }
}

@Composable
private fun SwipeRow(
    archive: Boolean,
    delete: Boolean,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismiss = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    val haptics = rememberHaptics()
    val armed = remember { arrayOf(SwipeToDismissBoxValue.Settled) }
    LaunchedEffect(dismiss.targetValue) {
        if (dismiss.targetValue != armed[0]) {
            if (dismiss.targetValue == SwipeToDismissBoxValue.Settled) haptics.tick() else haptics.threshold()
            armed[0] = dismiss.targetValue
        }
    }
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) {
            delay(700)
            dismiss.reset()
        }
    }
    SwipeToDismissBox(
        state = dismiss,
        modifier = modifier,
        enableDismissFromStartToEnd = archive,
        enableDismissFromEndToStart = delete,
        backgroundContent = {
            val toEnd = dismiss.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val scheme = MaterialTheme.colorScheme
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (toEnd) scheme.tertiaryContainer else scheme.errorContainer, capsuleShape())
                    .padding(horizontal = 28.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    painter = painterResource(if (toEnd) R.drawable.ic_archive else R.drawable.ic_delete),
                    contentDescription = null,
                    tint = if (toEnd) scheme.onTertiaryContainer else scheme.onErrorContainer,
                )
            }
        },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onArchive()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) { content() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OlderAction(state: MailboxUiState, onLoadOlder: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.Center) {
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = capsuleShape(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
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
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = capsuleShape(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
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
                TextButton(onClick = onRetry) { Text(stringResource(R.string.mailbox_retry)) }
                if (problems.any { it.failure == null }) {
                    TextButton(onClick = onOpenAccounts) { Text(stringResource(R.string.mailbox_open_accounts)) }
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
