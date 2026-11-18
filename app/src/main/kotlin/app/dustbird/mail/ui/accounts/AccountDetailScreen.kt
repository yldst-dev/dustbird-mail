package app.dustbird.mail.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustbird.mail.R
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.ui.accounts.manage.AccountDetailViewModel
import app.dustbird.mail.ui.accounts.manage.BasicsSection
import app.dustbird.mail.ui.accounts.manage.ConnectionSection
import app.dustbird.mail.ui.accounts.manage.DataSection
import app.dustbird.mail.ui.accounts.manage.FolderRolesSection
import app.dustbird.mail.ui.accounts.manage.IdentitiesSection
import app.dustbird.mail.ui.accounts.manage.ReauthForm
import app.dustbird.mail.ui.accounts.manage.Removal
import app.dustbird.mail.ui.accounts.manage.ServerSection
import app.dustbird.mail.ui.accounts.manage.reauthRequired
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.theme.FlatCard
import app.dustbird.mail.ui.theme.groupedTopAppBarColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountDetailRoute(accountId: Long, onBack: () -> Unit) {
    val viewModel = appViewModel(key = "account-detail-$accountId") { AccountDetailViewModel(it, accountId) }
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val removal by viewModel.removal.collectAsStateWithLifecycle()
    val account = accounts?.firstOrNull { it.id == accountId }

    LaunchedEffect(removal) {
        if (removal == Removal.Done) onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = groupedTopAppBarColors(),
                title = { Text(account?.displayName ?: stringResource(R.string.accounts_detail_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.accounts_back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val list = accounts
            when {
                list == null -> LoadingIndicator(Modifier.align(Alignment.Center))
                account == null -> if (removal == Removal.Idle) {
                    Text(stringResource(R.string.accounts_missing), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> AccountDetailContent(account, accentIndex = list.indexOfFirst { it.id == accountId }.takeIf { list.size > 1 }, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AccountDetailContent(account: MailAccount, accentIndex: Int?, viewModel: AccountDetailViewModel) {
    val identities by viewModel.identities.collectAsStateWithLifecycle()
    val appDefaultId by viewModel.appDefaultIdentityId.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val reauth by viewModel.reauth.collectAsStateWithLifecycle()
    val testSend by viewModel.testSend.collectAsStateWithLifecycle()
    val removal by viewModel.removal.collectAsStateWithLifecycle()
    val reauthRequired = account.reauthRequired(syncState)

    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (reauthRequired) {
            FlatCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.accounts_reauth_title), style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(stringResource(R.string.accounts_reauth_body))
                    ReauthForm(reauth, viewModel::reauthenticate)
                }
            }
        }
        BasicsSection(account)
        ConnectionSection(
            account = account,
            syncState = syncState,
            check = check,
            reauth = reauth,
            showReauth = !reauthRequired,
            onRecheck = viewModel::recheck,
            onReauth = viewModel::reauthenticate,
        )
        ServerSection(account)
        IdentitiesSection(account, accentIndex, identities, appDefaultId, testSend, viewModel::testSend)
        FolderRolesSection(account, folders)
        DataSection(account, removal, viewModel::remove, viewModel::cancelRemoval)
    }
}
