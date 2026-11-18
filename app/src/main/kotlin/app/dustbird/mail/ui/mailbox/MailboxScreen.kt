package app.dustbird.mail.ui.mailbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
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
import androidx.compose.ui.node.Ref
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import app.dustbird.mail.R
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.data.repo.MoveTarget
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.message.MessagePane
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.theme.CapsulePadding
import app.dustbird.mail.ui.theme.FlatMenu
import app.dustbird.mail.ui.theme.FlatSnackbarHost
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.capsuleItemShapes
import app.dustbird.mail.ui.theme.capsuleShape
import app.dustbird.mail.ui.theme.flatFabElevation
import app.dustbird.mail.ui.theme.groupedTopAppBarColors
import app.dustbird.mail.ui.theme.MenuEntry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MailboxRoute(
    onOpenMessage: (Long) -> Unit,
    onCompose: (ComposeArgs) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOutbox: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenAgent: (Long?) -> Unit = {},
    onOpenDrafts: () -> Unit = {},
) {
    val viewModel = appViewModel { MailboxViewModel(it) }
    val loaded by viewModel.state.collectAsStateWithLifecycle()
    val online by rememberOnline()
    val state = loaded
    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
    } else {
        MailboxScreen(state, online, viewModel, onOpenMessage, onCompose, onOpenAccounts, onOpenSettings, onOpenOutbox, onOpenSearch, onOpenAgent, onOpenDrafts)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun MailboxScreen(
    state: MailboxUiState,
    online: Boolean,
    viewModel: MailboxViewModel,
    onOpenMessage: (Long) -> Unit,
    onCompose: (ComposeArgs) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOutbox: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAgent: (Long?) -> Unit,
    onOpenDrafts: () -> Unit,
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
    val confirmDelete by viewModel.confirmDelete.collectAsStateWithLifecycle()
    val collapsedAccounts by viewModel.collapsedAccounts.collectAsStateWithLifecycle()
    val draftCount by viewModel.draftCount.collectAsStateWithLifecycle()
    var moving by remember { mutableStateOf<List<Long>?>(null) }
    val selecting = state.selection.isNotEmpty()
    val motion = MaterialTheme.motionScheme
    val selectionState = remember { Ref<MailboxUiState>() }
    if (selecting) selectionState.value = state

    LaunchedEffect(viewModel, resources) {
        viewModel.events.collect { event ->
            launch {
                when (event) {
                    is MailboxEvent.Notice -> snackbar.showSnackbar(resources.getString(event.text, *event.args.toTypedArray()))
                    is MailboxEvent.Moved -> {
                        snackbar.currentSnackbarData?.dismiss()
                        val result = snackbar.showSnackbar(
                            message = resources.getString(event.text, event.ticket.count),
                            actionLabel = resources.getString(R.string.mailbox_undo),
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undo(event.ticket) else viewModel.commit(event.ticket)
                    }
                }
            }
        }
    }
    BackHandler(enabled = selecting) { viewModel.clearSelection() }
    BackHandler(enabled = drawerState.isOpen) { coroutineScope.launch { drawerState.close() } }

    fun startMove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val accounts = state.messages.orEmpty().filter { it.id in ids }.map { it.accountId }.distinct()
        if (accounts.size == 1) {
            moving = ids.toList()
        } else {
            coroutineScope.launch { snackbar.showSnackbar(resources.getString(R.string.mailbox_move_mixed)) }
        }
    }

    val inDrafts = state.scopeRole() == FolderRole.DRAFTS
    val rowActions = remember(viewModel, twoPane, onOpenMessage, inDrafts) {
        MailboxRowActions(
            onOpen = { id ->
                coroutineScope.launch {
                    val draftId = if (inDrafts) viewModel.draftFor(id) else null
                    when {
                        draftId != null -> onCompose(ComposeArgs(draftId = draftId))
                        twoPane -> viewModel.select(id)
                        else -> onOpenMessage(id)
                    }
                }
            },
            onToggle = viewModel::toggleSelection,
            onArchive = { viewModel.move(listOf(it), MoveTarget.Role(FolderRole.ARCHIVE)) },
            onDelete = { viewModel.delete(listOf(it)) },
            onFilter = viewModel::setFilter,
        )
    }
    val menu: @Composable () -> Unit = {
        MailboxMenu(
            state = state,
            collapsed = collapsedAccounts,
            onToggleAccount = viewModel::toggleAccount,
            drafts = draftCount,
            onOpenDrafts = {
                coroutineScope.launch { drawerState.close() }
                onOpenDrafts()
            },
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
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                AnimatedContent(
                    targetState = selecting,
                    transitionSpec = {
                        if (animations) fadeIn(motion.slowEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) else EnterTransition.None togetherWith ExitTransition.None
                    },
                    label = "mailbox top bar",
                ) { chosen ->
                    if (chosen) {
                        SelectionBar(
                            state = selectionState.value ?: state,
                            subtitle = scopeTitle(state).takeIf { (state.unreadInScope() ?: 0) > 0 },
                            scrollBehavior = scrollBehavior,
                            onClose = viewModel::clearSelection,
                            onArchive = { viewModel.move(state.selection, MoveTarget.Role(FolderRole.ARCHIVE)) },
                            onDelete = { viewModel.delete(state.selection) },
                            onSeen = { viewModel.setSeen(state.selection, it) },
                            onFlagged = { viewModel.setFlagged(state.selection, it) },
                            onMove = { startMove(state.selection) },
                            onSpam = { viewModel.move(state.selection, MoveTarget.Role(if (it) FolderRole.SPAM else FolderRole.INBOX)) },
                            onSelectAll = viewModel::selectAll,
                        )
                    } else {
                        MailboxTopBar(
                            state = state,
                            showMenuButton = !permanentMenu,
                            scrollBehavior = scrollBehavior,
                            onOpenMenu = { coroutineScope.launch { drawerState.open() } },
                            onRefresh = viewModel::refresh,
                            onOpenOutbox = onOpenOutbox,
                            onOpenDrafts = onOpenDrafts,
                            onOpenAccounts = onOpenAccounts,
                            onOpenSettings = onOpenSettings,
                            onOpenSearch = onOpenSearch,
                            onOpenAgent = { onOpenAgent(null) },
                        )
                    }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = !selecting,
                    enter = if (animations) scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()) else EnterTransition.None,
                    exit = if (animations) scaleOut(motion.fastEffectsSpec()) + fadeOut(motion.fastEffectsSpec()) else ExitTransition.None,
                    label = "compose button",
                ) {
                    val label = stringResource(R.string.mailbox_compose)
                    SmallExtendedFloatingActionButton(
                        text = { Text(label) },
                        icon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = label) },
                        onClick = { onCompose(ComposeArgs()) },
                        expanded = fabExpanded || !animations,
                        shape = Pill,
                        elevation = flatFabElevation(),
                    )
                }
            },
            snackbarHost = { FlatSnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (twoPane) {
                    ListDetailPaneScaffold(
                        directive = directive,
                        value = remember { ThreePaneScaffoldValue(PaneAdaptedValue.Expanded, PaneAdaptedValue.Expanded, PaneAdaptedValue.Hidden) },
                        modifier = Modifier.fillMaxSize(),
                        listPane = {
                            AnimatedPane(Modifier.preferredWidth(380.dp)) {
                                MailboxPane(state, online, listState, true, rowActions, viewModel::refresh, viewModel::loadOlder, onOpenAccounts, Modifier.fillMaxSize())
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
                                    Box(Modifier.padding(end = 16.dp, bottom = 88.dp)) {
                                        key(selected) {
                                            MessagePane(selected, onCompose, onGone = { viewModel.select(null) }, onOpenAgent = onOpenAgent)
                                        }
                                    }
                                }
                            }
                        },
                    )
                } else {
                    MailboxPane(state, online, listState, false, rowActions, viewModel::refresh, viewModel::loadOlder, onOpenAccounts, Modifier.fillMaxSize())
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
            drawerContent = {
                ModalDrawerSheet(drawerShape = MaterialTheme.shapes.extraExtraLarge.copy(topStart = ZeroCornerSize, bottomStart = ZeroCornerSize)) { menu() }
            },
            drawerState = drawerState,
            content = screen,
        )
    }

    if (confirmDelete != null) {
        AlertDialog(
            shape = capsuleShape(),
            onDismissRequest = { viewModel.answerDeleteConfirm(false) },
            title = { Text(stringResource(R.string.mailbox_delete_forever_title)) },
            text = { Text(stringResource(R.string.mailbox_delete_forever_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.answerDeleteConfirm(true) }) {
                    Text(stringResource(R.string.mailbox_delete_forever_ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.answerDeleteConfirm(false) }) { Text(stringResource(R.string.mailbox_cancel)) } },
        )
    }
    moving?.let { ids ->
        val accountId = state.messages.orEmpty().firstOrNull { it.id in ids }?.accountId
        FolderPicker(
            folders = state.folders.filter { it.accountId == accountId },
            onPick = { folder ->
                moving = null
                viewModel.move(ids, MoveTarget.Folder(folder.id))
            },
            onDismiss = { moving = null },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderPicker(folders: List<FolderEntity>, onPick: (FolderEntity) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        shape = capsuleShape(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mailbox_move_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(folders, key = { it.id }) { folder ->
                    ListItem(
                        onClick = { onPick(folder) },
                        shapes = capsuleItemShapes(),
                        contentPadding = CapsulePadding,
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    ) { Text(folder.role?.let { stringResource(folderLabelRes(it)) } ?: folder.displayName()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mailbox_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MailboxTopBar(
    state: MailboxUiState,
    showMenuButton: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    onOpenMenu: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOutbox: () -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAgent: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    val unread = state.unreadInScope()?.takeIf { it > 0 }
    val subtitle = unread?.let { stringResource(R.string.mailbox_subtitle_unread, it) }
    MediumFlexibleTopAppBar(
        colors = groupedTopAppBarColors(),
        title = { Text(scopeTitle(state), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        subtitle = subtitle?.let { text -> @Composable { Text(text) } },
        navigationIcon = {
            if (showMenuButton) {
                IconButton(onClick = onOpenMenu) {
                    Icon(painterResource(R.drawable.ic_menu), contentDescription = stringResource(R.string.mailbox_action_open_menu))
                }
            }
        },
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.mailbox_action_search))
            }
            IconButton(onClick = onOpenAgent) {
                Icon(painterResource(R.drawable.ic_bird), contentDescription = stringResource(R.string.mailbox_action_agent), modifier = Modifier.size(26.dp))
            }
            if (state.outboxPending > 0) {
                IconButton(onClick = onOpenOutbox) {
                    val pending = stringResource(R.string.mailbox_outbox_pending, state.outboxPending)
                    BadgedBox(badge = { Badge { Text(state.outboxPending.toString(), modifier = Modifier.semantics { contentDescription = pending }) } }) {
                        Icon(painterResource(R.drawable.ic_outbox), contentDescription = stringResource(R.string.mailbox_action_outbox))
                    }
                }
            }
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.mailbox_action_more))
                }
                FlatMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                    groups = listOf(
                        listOf(
                            MenuEntry(R.string.mailbox_action_refresh, R.drawable.ic_refresh, onRefresh),
                            MenuEntry(R.string.mailbox_action_outbox, R.drawable.ic_outbox, onOpenOutbox),
                            MenuEntry(R.string.drafts_title, R.drawable.ic_draft, onOpenDrafts),
                        ),
                        listOf(
                            MenuEntry(R.string.mailbox_action_accounts, R.drawable.ic_person, onOpenAccounts),
                            MenuEntry(R.string.mailbox_action_settings, R.drawable.ic_tune, onOpenSettings),
                        ),
                    ),
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectionBar(
    state: MailboxUiState,
    subtitle: String?,
    scrollBehavior: TopAppBarScrollBehavior,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onSeen: (Boolean) -> Unit,
    onFlagged: (Boolean) -> Unit,
    onMove: () -> Unit,
    onSpam: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    val chosen = state.messages.orEmpty().filter { it.id in state.selection }
    val anyUnread = chosen.any { !it.seen }
    val anyUnstarred = chosen.any { !it.flagged }
    val inSpam = state.scopeRole() == FolderRole.SPAM
    MediumFlexibleTopAppBar(
        colors = groupedTopAppBarColors(),
        title = { Text(stringResource(R.string.mailbox_selection_count, state.selection.size), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        subtitle = subtitle?.let { text -> @Composable { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.mailbox_selection_close))
            }
        },
        actions = {
            if (state.scopeRole() == FolderRole.INBOX) {
                IconButton(onClick = onArchive) {
                    Icon(painterResource(R.drawable.ic_archive), contentDescription = stringResource(R.string.mailbox_action_archive))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.mailbox_action_delete))
            }
            IconButton(onClick = { onSeen(anyUnread) }) {
                Icon(
                    painterResource(if (anyUnread) R.drawable.ic_mark_email_read else R.drawable.ic_mark_email_unread),
                    contentDescription = stringResource(if (anyUnread) R.string.mailbox_action_mark_read else R.string.mailbox_action_mark_unread),
                )
            }
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.mailbox_action_more))
                }
                FlatMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                    groups = listOf(
                        listOf(
                            MenuEntry(if (anyUnstarred) R.string.mailbox_action_star else R.string.mailbox_action_unstar, R.drawable.ic_star) { onFlagged(anyUnstarred) },
                            MenuEntry(R.string.mailbox_action_move, R.drawable.ic_folder, onMove),
                            MenuEntry(
                                if (inSpam) R.string.mailbox_action_not_spam else R.string.mailbox_action_spam,
                                if (inSpam) R.drawable.ic_inbox else R.drawable.ic_report,
                            ) { onSpam(!inSpam) },
                        ),
                        listOf(MenuEntry(R.string.mailbox_action_select_all, R.drawable.ic_select_all, onSelectAll)),
                    ),
                )
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
        is MailboxScope.Folder -> {
            val folder = state.folders.firstOrNull { it.id == target.folderId }?.displayName().orEmpty()
            val account = state.accounts.firstOrNull { it.id == target.accountId }
            if (multiple && account != null) stringResource(R.string.mailbox_title_account_folder, account.label, folder) else folder
        }
    }
}
