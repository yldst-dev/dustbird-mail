package app.dustbird.mail.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accounts_back)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.accounts_add)) },
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
                else -> Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
                    list.forEachIndexed { index, account ->
                        if (index > 0) HorizontalDivider()
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).clickable(
                    onClickLabel = stringResource(R.string.accounts_open_label, account.displayName),
                    role = Role.Button,
                    onClick = onOpen,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AccentLabel(account.label, accentIndex)
                Text(
                    account.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.accounts_row_service_login, stringResource(providerNameRes(account.providerId)), account.username),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(connectionStatusRes(account, syncState)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (problem) FontWeight.Bold else FontWeight.Normal,
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (accentIndex != null) {
                MoveButtons(
                    upDescription = stringResource(R.string.accounts_move_up, account.displayName),
                    downDescription = stringResource(R.string.accounts_move_down, account.displayName),
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                )
            }
        }
        if (account.reauthRequired(syncState)) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.accounts_reauth_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.accounts_reauth_body), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = onOpen,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(R.string.accounts_reauth_action)) }
                }
            }
        }
        StageList(account.stages)
    }
}
