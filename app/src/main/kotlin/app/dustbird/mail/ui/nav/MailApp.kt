package app.dustbird.mail.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import app.dustbird.mail.ui.message.MessageRoute
import app.dustbird.mail.ui.outbox.OutboxRoute
import kotlinx.coroutines.flow.first

@Composable
fun MailApp(openMessageId: Long?) {
    val container = LocalAppContainer.current
    val start by produceState<String?>(initialValue = null) {
        value = if (container.accounts.accounts.first().isEmpty()) Routes.ADD_ACCOUNT else Routes.MAILBOX
    }
    val startDestination = start ?: return
    val navController = rememberNavController()

    LaunchedEffect(openMessageId) {
        if (openMessageId != null && startDestination == Routes.MAILBOX) navController.navigate(Routes.message(openMessageId))
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.MAILBOX) {
            MailboxRoute(
                onOpenMessage = { navController.navigate(Routes.message(it)) },
                onCompose = { navController.navigate(Routes.compose(ComposeArgs())) },
                onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenOutbox = { navController.navigate(Routes.OUTBOX) },
            )
        }
        composable(Routes.MESSAGE, listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            MessageRoute(
                messageId = entry.arguments!!.getLong("id"),
                onBack = { navController.popBackStack() },
                onCompose = { navController.navigate(Routes.compose(it)) },
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
            ComposeRoute(args = args, onClose = { navController.popBackStack() })
        }
        composable(Routes.OUTBOX) { OutboxRoute(onBack = { navController.popBackStack() }) }
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
}
