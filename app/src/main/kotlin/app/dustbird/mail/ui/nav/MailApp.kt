package app.dustbird.mail.ui.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.accounts.AccountDetailRoute
import app.dustbird.mail.ui.accounts.AccountsRoute
import app.dustbird.mail.ui.accounts.AddAccountRoute
import app.dustbird.mail.ui.accounts.LicensesRoute
import app.dustbird.mail.ui.accounts.SettingsRoute
import app.dustbird.mail.ui.compose.ComposeRoute
import app.dustbird.mail.ui.mailbox.MailboxRoute
import app.dustbird.mail.ui.mailbox.rememberAnimationsEnabled
import app.dustbird.mail.ui.message.MessageRoute
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dustbird.mail.ui.outbox.DraftsRoute
import app.dustbird.mail.ui.outbox.SendTracker
import app.dustbird.mail.ui.outbox.OutboxRoute
import kotlinx.coroutines.flow.first
import app.dustbird.mail.ui.search.SearchRoute
import app.dustbird.mail.ui.agent.AgentSettingsRoute
import app.dustbird.mail.ui.agent.AgentRoute

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MailApp(
    openMessageId: Long?,
    openAccountId: Long?,
    composeRequest: Int = 0,
    openAgentSession: Long? = null,
    targetRequest: Int = 0,
    outboxRequest: Int = 0,
) {
    val container = LocalAppContainer.current
    val start by produceState<String?>(initialValue = null) {
        value = if (container.accounts.accounts.first().isEmpty()) Routes.ADD_ACCOUNT else Routes.MAILBOX
    }
    val startDestination = start ?: return
    val navController = rememberNavController()

    suspend fun ready() = container.accounts.accounts.first().isNotEmpty()

    LaunchedEffect(openMessageId, targetRequest) {
        if (openMessageId != null && ready()) navController.navigate(Routes.message(openMessageId))
    }
    LaunchedEffect(composeRequest) {
        if (composeRequest == 0) return@LaunchedEffect
        if (ready()) navController.navigate(Routes.compose(ComposeArgs())) else container.prefill.value = null
    }
    LaunchedEffect(openAgentSession, targetRequest) {
        if (openAgentSession != null && ready()) navController.navigate(Routes.agent(sessionId = openAgentSession))
    }
    LaunchedEffect(openAccountId, targetRequest) {
        if (openAccountId != null && ready()) navController.navigate(Routes.accountDetail(openAccountId))
    }
    LaunchedEffect(outboxRequest) {
        if (outboxRequest > 0 && ready()) navController.navigate(Routes.OUTBOX) { launchSingleTop = true }
    }

    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val onMailbox = navController.currentBackStackEntryAsState().value?.destination?.route == Routes.MAILBOX
    LaunchedEffect(onMailbox) {
        if (onMailbox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !container.settings.notificationPromptDone.first()
        ) {
            container.settings.markNotificationPromptDone()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val animations = rememberAnimationsEnabled()
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Box(Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { if (animations) slideInHorizontally(spatial) { it / 4 } + fadeIn(effects) else EnterTransition.None },
        exitTransition = { if (animations) slideOutHorizontally(spatial) { -it / 4 } + fadeOut(effects) else ExitTransition.None },
        popEnterTransition = { if (animations) slideInHorizontally(spatial) { -it / 4 } + fadeIn(effects) else EnterTransition.None },
        popExitTransition = { if (animations) slideOutHorizontally(spatial) { it / 4 } + fadeOut(effects) else ExitTransition.None },
    ) {
        composable(Routes.MAILBOX) {
            MailboxRoute(
                onOpenMessage = { navController.navigate(Routes.message(it)) },
                onCompose = { navController.navigate(Routes.compose(it)) },
                onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenOutbox = { navController.navigate(Routes.OUTBOX) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenAgent = { navController.navigate(Routes.agent(it)) },
                onOpenDrafts = { navController.navigate(Routes.DRAFTS) },
            )
        }
        composable(Routes.DRAFTS) {
            DraftsRoute(onBack = { navController.popBackStack() }, onOpen = { navController.navigate(Routes.compose(ComposeArgs(draftId = it))) })
        }
        composable(
            Routes.AGENT,
            listOf(
                navArgument("message") { type = NavType.LongType; defaultValue = -1L },
                navArgument("session") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            AgentRoute(
                messageId = entry.arguments!!.getLong("message").takeIf { it >= 0 },
                sessionId = entry.arguments!!.getLong("session").takeIf { it >= 0 },
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.AGENT_SETTINGS) },
                onOpenDraft = { navController.navigate(Routes.compose(ComposeArgs(draftId = it))) },
            )
        }
        composable(Routes.AGENT_SETTINGS) { AgentSettingsRoute(onBack = { navController.popBackStack() }) }
        composable(Routes.SEARCH) {
            SearchRoute(onBack = { navController.popBackStack() }, onOpenMessage = { navController.navigate(Routes.message(it)) })
        }
        composable(Routes.MESSAGE, listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            MessageRoute(
                messageId = entry.arguments!!.getLong("id"),
                onBack = { navController.popBackStack() },
                onCompose = { navController.navigate(Routes.compose(it)) },
                onOpenAgent = { navController.navigate(Routes.agent(it)) },
            )
        }
        composable(
            Routes.COMPOSE,
            listOf(
                navArgument("draft") { type = NavType.LongType; defaultValue = -1L },
                navArgument("mode") { type = NavType.StringType; defaultValue = ComposeMode.NEW.name },
                navArgument("source") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val arguments = entry.arguments!!
            val args = remember(entry) {
                ComposeArgs(
                    draftId = arguments.getLong("draft").takeIf { it >= 0 },
                    mode = ComposeMode.valueOf(arguments.getString("mode") ?: ComposeMode.NEW.name),
                    sourceMessageId = arguments.getLong("source").takeIf { it >= 0 },
                )
            }
            ComposeRoute(args = args, onClose = { navController.popBackStack() }, onOpenAgentSettings = { navController.navigate(Routes.AGENT_SETTINGS) })
        }
        composable(Routes.OUTBOX) {
            OutboxRoute(onBack = { navController.popBackStack() }, onEditDraft = { navController.navigate(Routes.compose(ComposeArgs(draftId = it))) })
        }
        composable(Routes.ACCOUNTS) {
            AccountsRoute(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.ADD_ACCOUNT) },
                onOpenAccount = { navController.navigate(Routes.accountDetail(it)) },
            )
        }
        composable(Routes.ADD_ACCOUNT) {
            AddAccountRoute(
                onBack = { if (!navController.popBackStack()) Unit },
                onDone = {
                    navController.navigate(Routes.MAILBOX) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onOpenAgentSettings = { navController.navigate(Routes.AGENT_SETTINGS) },
            )
        }
        composable(Routes.ACCOUNT_DETAIL, listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            AccountDetailRoute(accountId = entry.arguments!!.getLong("id"), onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = { navController.popBackStack() }, onOpenLicenses = { navController.navigate(Routes.LICENSES) })
        }
        composable(Routes.LICENSES) { LicensesRoute(onBack = { navController.popBackStack() }) }
    }
    SendTracker(
        onEdit = { navController.navigate(Routes.compose(ComposeArgs(draftId = it))) },
        onOpenOutbox = { navController.navigate(Routes.OUTBOX) { launchSingleTop = true } },
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
    )
    }
}
