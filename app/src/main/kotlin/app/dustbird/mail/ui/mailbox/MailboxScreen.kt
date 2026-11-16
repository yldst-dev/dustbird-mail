package app.dustbird.mail.ui.mailbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import app.dustbird.mail.R
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.message.MessagePane
import app.dustbird.mail.ui.nav.ComposeArgs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MailboxRoute(
    onOpenMessage: (Long) -> Unit,
    onCompose: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOutbox: () -> Unit,
) {
    val viewModel = appViewModel { MailboxViewModel(it) }
    val loaded by viewModel.state.collectAsStateWithLifecycle()
    val online by rememberOnline()
    val state = loaded
    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
    } else {
        MailboxScreen(
            state = state,
            online = online,
            viewModel = viewModel,
            onOpenMessage = onOpenMessage,
            onCompose = onCompose,
            onOpenAccounts = onOpenAccounts,
            onOpenSettings = onOpenSettings,
            onOpenOutbox = onOpenOutbox,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun MailboxScreen(
    state: MailboxUiState,
    online: Boolean,
    viewModel: MailboxViewModel,
    onOpenMessage: (Long) -> Unit,
    onCompose: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOutbox: () -> Unit,
) {
    val adaptive = currentWindowAdaptiveInfoV2()
    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(adaptive)
    val twoPane = directive.maxHorizontalPartitions > 1
    val permanentMenu = adaptive.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val animations = rememberAnimationsEnabled()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    LaunchedEffect(viewModel, resources) { viewModel.notices.collect { snackbar.showSnackbar(resources.getString(it)) } }

    val composeFromPane: (ComposeArgs) -> Unit = { args ->
        if (args == ComposeArgs()) onCompose() else state.selectedId?.let(onOpenMessage)
    }
    val menu: @Composable () -> Unit = {
        MailboxMenu(
            state = state,
            onSelect = { target ->
                viewModel.openScope(target)
                coroutineScope.launch {
                    listState.scrollToItem(0)
                    drawerState.close()
                }
            },
        )
    }
    val screen: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                MailboxTopBar(
                    state = state,
                    showMenuButton = !permanentMenu,
                    scrollBehavior = scrollBehavior,
                    onOpenMenu = { coroutineScope.launch { drawerState.open() } },
                    onRefresh = viewModel::refresh,
                    onOpenOutbox = onOpenOutbox,
                    onOpenAccounts = onOpenAccounts,
                    onOpenSettings = onOpenSettings,
                )
            },
            floatingActionButton = {
                val label = stringResource(R.string.mailbox_compose)
                val expanded = fabExpanded || !animations
                ExtendedFloatingActionButton(
                    text = { Text(label) },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = onCompose,
                    expanded = expanded,
                    modifier = if (expanded) Modifier else Modifier.semantics { contentDescription = label },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (twoPane) {
                    ListDetailPaneScaffold(
                        directive = directive,
                        value = remember { ThreePaneScaffoldValue(PaneAdaptedValue.Expanded, PaneAdaptedValue.Expanded, PaneAdaptedValue.Hidden) },
                        modifier = Modifier.fillMaxSize(),
                        listPane = {
                            AnimatedPane(Modifier.preferredWidth(380.dp)) {
                                MailboxPane(
                                    state = state,
                                    online = online,
                                    listState = listState,
                                    selectable = true,
                                    onOpenMessage = viewModel::select,
                                    onRefresh = viewModel::refresh,
                                    onLoadOlder = viewModel::loadOlder,
                                    onOpenAccounts = onOpenAccounts,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        },
                        detailPane = {
                            AnimatedPane {
                                val selected = state.selectedId
                                if (selected == null) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = stringResource(R.string.mailbox_pick_message),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    key(selected) { MessagePane(selected, composeFromPane) }
                                }
                            }
                        },
                    )
                } else {
                    MailboxPane(
                        state = state,
                        online = online,
                        listState = listState,
                        selectable = false,
                        onOpenMessage = onOpenMessage,
                        onRefresh = viewModel::refresh,
                        onLoadOlder = viewModel::loadOlder,
                        onOpenAccounts = onOpenAccounts,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (permanentMenu) {
        PermanentNavigationDrawer(
            drawerContent = { PermanentDrawerSheet(Modifier.width(280.dp)) { menu() } },
            content = screen,
        )
    } else {
        ModalNavigationDrawer(
            drawerContent = { ModalDrawerSheet { menu() } },
            drawerState = drawerState,
            content = screen,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MailboxTopBar(
    state: MailboxUiState,
    showMenuButton: Boolean,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onOpenMenu: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOutbox: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    val unread = state.unreadInScope()?.takeIf { it > 0 }
    val subtitle = unread?.let { stringResource(R.string.mailbox_subtitle_unread, it) }
    MediumFlexibleTopAppBar(
        title = { Text(scopeTitle(state), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        subtitle = subtitle?.let { text -> @Composable { Text(text) } },
        navigationIcon = {
            if (showMenuButton) {
                IconButton(onClick = onOpenMenu) { Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.mailbox_action_open_menu)) }
            }
        },
        actions = {
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.mailbox_action_refresh))
            }
            IconButton(onClick = onOpenOutbox) {
                val pending = stringResource(R.string.mailbox_outbox_pending, state.outboxPending)
                BadgedBox(
                    badge = {
                        if (state.outboxPending > 0) {
                            Badge { Text(state.outboxPending.toString(), modifier = Modifier.semantics { contentDescription = pending }) }
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.mailbox_action_outbox))
                }
            }
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mailbox_action_more))
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mailbox_action_accounts)) },
                        onClick = {
                            overflowOpen = false
                            onOpenAccounts()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mailbox_action_settings)) },
                        onClick = {
                            overflowOpen = false
                            onOpenSettings()
                        },
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun scopeTitle(state: MailboxUiState): String {
    val multiple = state.accounts.size > 1
    return when (val target = state.scope) {
        MailboxScope.Unified ->
            stringResource(if (multiple) R.string.mailbox_title_unified else folderLabelRes(FolderRole.INBOX))
        is MailboxScope.Account -> {
            val folder = stringResource(folderLabelRes(target.role))
            val account = state.accounts.firstOrNull { it.id == target.accountId }
            if (multiple && account != null) stringResource(R.string.mailbox_title_account_folder, account.label, folder) else folder
        }
    }
}
