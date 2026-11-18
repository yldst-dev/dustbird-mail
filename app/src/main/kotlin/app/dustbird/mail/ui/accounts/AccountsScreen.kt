package app.dustbird.mail.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustbird.mail.R
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.sync.AccountSyncState
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.accounts.manage.AccentLabel
import app.dustbird.mail.ui.accounts.manage.MoveButtons
import app.dustbird.mail.ui.accounts.manage.StageList
import app.dustbird.mail.ui.accounts.manage.connectionStatusRes
import app.dustbird.mail.ui.accounts.manage.hasConnectionProblem
import app.dustbird.mail.ui.accounts.manage.providerNameRes
import app.dustbird.mail.ui.accounts.manage.reauthRequired
import app.dustbird.mail.ui.theme.FlatCard
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.CapsulePadding
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.cardItemShapes
import app.dustbird.mail.ui.theme.flatFabElevation
import app.dustbird.mail.ui.theme.groupedTopAppBarColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountsRoute(onBack: () -> Unit, onAdd: () -> Unit, onOpenAccount: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val accounts by container.accounts.accounts.collectAsStateWithLifecycle<List<MailAccount>?>(null)
    val syncStates by container.sync.state.collectAsStateWithLifecycle()

    fun move(index: Int, delta: Int) {
        val ids = accounts.orEmpty().map { it.id }.toMutableList()
        val target = index + delta
        if (index !in ids.indices || target !in ids.indices) return
        ids.add(target, ids.removeAt(index))
        scope.launch { container.accounts.reorderAccounts(ids) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = groupedTopAppBarColors(),
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.accounts_back)) }
                },
            )
        },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.accounts_add)) },
                text = { Text(stringResource(R.string.accounts_add)) },
                shape = Pill,
                elevation = flatFabElevation(),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val list = accounts
            when {
                list == null -> LoadingIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Column(Modifier.widthIn(max = 640.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.accounts_empty_title), style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(stringResource(R.string.accounts_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Column(
                    Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    list.forEachIndexed { index, account ->
                        AccountRow(
                            account = account,
                            accentIndex = index.takeIf { list.size > 1 },
                            syncState = syncStates[account.id],
                            canMoveUp = index > 0,
                            canMoveDown = index < list.lastIndex,
                            onOpen = { onOpenAccount(account.id) },
                            onMoveUp = { move(index, -1) },
                            onMoveDown = { move(index, 1) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountRow(
    account: MailAccount,
    accentIndex: Int?,
    syncState: AccountSyncState?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val problem = account.hasConnectionProblem(syncState)
    val reauth = account.reauthRequired(syncState)
    val count = if (reauth) 3 else 2
    val openLabel = stringResource(R.string.accounts_open_label, account.displayName)
    Column(verticalArrangement = Arrangement.spacedBy(CapsuleGap)) {
        ListItem(
            onClick = onOpen,
            shapes = cardItemShapes(),
            contentPadding = CapsulePadding,
            modifier = Modifier.semantics { onClick(label = openLabel, action = null) },
            overlineContent = { AccentLabel(account.label, accentIndex) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.accounts_row_service_login, stringResource(providerNameRes(account.providerId)), account.username))
                    Text(
                        stringResource(connectionStatusRes(account, syncState)),
                        style = if (problem) MaterialTheme.typography.bodyMediumEmphasized else MaterialTheme.typography.bodyMedium,
                        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            trailingContent = if (accentIndex == null) {
                null
            } else {
                {
                    MoveButtons(
                        upDescription = stringResource(R.string.accounts_move_up, account.displayName),
                        downDescription = stringResource(R.string.accounts_move_down, account.displayName),
                        canMoveUp = canMoveUp,
                        canMoveDown = canMoveDown,
                        onMoveUp = onMoveUp,
                        onMoveDown = onMoveDown,
                    )
                }
            },
        ) { Text(account.displayName, style = MaterialTheme.typography.titleMediumEmphasized, overflow = TextOverflow.Ellipsis) }
        if (reauth) {
            FlatCard(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.accounts_reauth_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(stringResource(R.string.accounts_reauth_body), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = onOpen,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(R.string.accounts_reauth_action)) }
                }
            }
        }
        FlatCard { StageList(account.stages, Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
    }
}
