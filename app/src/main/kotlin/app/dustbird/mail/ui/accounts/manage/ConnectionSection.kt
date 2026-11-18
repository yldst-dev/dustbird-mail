package app.dustbird.mail.ui.accounts.manage

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.net.CheckResult
import app.dustbird.mail.sync.AccountSyncState
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.pillFieldColors

@Composable
fun ConnectionSection(
    account: MailAccount,
    syncState: AccountSyncState?,
    check: Check,
    reauth: Reauth,
    showReauth: Boolean,
    onRecheck: () -> Unit,
    onReauth: (String) -> Unit,
) {
    val problem = account.hasConnectionProblem(syncState)
    Section(R.string.accounts_connection_heading) {
        Text(
            stringResource(connectionStatusRes(account, syncState)),
            style = if (problem) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
            color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        StageList(account.stages)
        Text(stringResource(R.string.accounts_connection_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onRecheck, enabled = check != Check.Running) { Text(stringResource(R.string.accounts_recheck)) }
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (check) {
                Check.Idle -> Unit
                Check.Running -> Waiting(R.string.accounts_rechecking)
                Check.NoCredentials -> Text(stringResource(R.string.accounts_check_no_credentials), style = MaterialTheme.typography.bodyLargeEmphasized, color = MaterialTheme.colorScheme.error)
                is Check.Done -> {
                    CheckLine(R.string.accounts_stage_imap_auth, check.imap)
                    CheckLine(R.string.accounts_stage_smtp_auth, check.smtp)
                }
            }
        }
        if (showReauth) {
            Text(stringResource(R.string.accounts_reauth_form_heading), style = MaterialTheme.typography.titleMedium)
            ReauthForm(reauth, onReauth)
        }
    }
}

@Composable
fun ReauthForm(reauth: Reauth, onSubmit: (String) -> Unit) {
    val password = remember { TextFieldState() }
    LaunchedEffect(reauth) {
        if (reauth != Reauth.Idle && reauth != Reauth.Checking) password.clearText()
    }
    Text(stringResource(R.string.accounts_reauth_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SecureTextField(
        state = password,
        label = { Text(stringResource(R.string.accounts_password)) },
        modifier = Modifier.fillMaxWidth(),
        shape = Pill,
        colors = pillFieldColors(),
        textObfuscationMode = TextObfuscationMode.Hidden,
        enabled = reauth != Reauth.Checking,
    )
    Button(
        onClick = { onSubmit(password.text.toString()) },
        enabled = password.text.isNotEmpty() && reauth != Reauth.Checking,
    ) { Text(stringResource(R.string.accounts_reauth_submit)) }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (reauth) {
            Reauth.Idle -> Unit
            Reauth.Checking -> Waiting(R.string.accounts_reauth_checking)
            is Reauth.Failed -> {
                Text(stringResource(R.string.accounts_reauth_not_saved), style = MaterialTheme.typography.bodyLargeEmphasized, color = MaterialTheme.colorScheme.error)
                CheckLine(R.string.accounts_stage_imap_auth, reauth.imap)
                CheckLine(R.string.accounts_stage_smtp_auth, reauth.smtp)
            }
            Reauth.StoreFailed -> Text(stringResource(R.string.accounts_reauth_store_failed), style = MaterialTheme.typography.bodyLargeEmphasized, color = MaterialTheme.colorScheme.error)
            is Reauth.Done -> {
                Text(stringResource(R.string.accounts_reauth_done), style = MaterialTheme.typography.bodyLargeEmphasized)
                if (reauth.smtp is CheckResult.Failed) CheckLine(R.string.accounts_stage_smtp_auth, reauth.smtp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Waiting(@StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(32.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckLine(@StringRes stage: Int, result: CheckResult) {
    val failed = result as? CheckResult.Failed
    Text(
        stringResource(
            R.string.accounts_check_line,
            stringResource(stage),
            stringResource(failed?.failure?.messageRes() ?: R.string.accounts_stage_ok),
        ),
        style = if (failed == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyMediumEmphasized,
        color = if (failed == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
}
