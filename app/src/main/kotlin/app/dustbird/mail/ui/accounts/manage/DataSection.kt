package app.dustbird.mail.ui.accounts.manage

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.dustbird.mail.R
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.ui.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun DataSection(account: MailAccount, removal: Removal, onRemove: (Boolean) -> Unit, onCancelRemoval: () -> Unit) {
    val container = LocalAppContainer.current
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    var confirmCache by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }

    Section(R.string.accounts_data_heading) {
        Text(stringResource(R.string.accounts_data_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { confirmCache = true }) { Text(stringResource(R.string.accounts_cache_clear)) }
        note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = { confirmDelete = true },
            enabled = removal != Removal.Working,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { Text(stringResource(R.string.accounts_delete), fontWeight = FontWeight.Bold) }
        if (removal == Removal.Working) Waiting(R.string.accounts_deleting)
    }

    if (confirmCache) {
        ConfirmDialog(
            title = R.string.accounts_cache_title,
            text = stringResource(R.string.accounts_cache_text),
            confirm = R.string.accounts_cache_confirm,
            destructive = true,
            onDismiss = { confirmCache = false },
            onConfirm = {
                confirmCache = false
                scope.launch {
                    container.maintenance.clearAccountCache(account.id)
                    note = resources.getString(R.string.accounts_cache_done)
                }
            },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = R.string.accounts_delete_title,
            text = stringResource(R.string.accounts_delete_text, account.displayName),
            confirm = R.string.accounts_delete_confirm,
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onRemove(false)
            },
        )
    }

    if (removal is Removal.Blocked) {
        ConfirmDialog(
            title = R.string.accounts_delete_blocked_title,
            text = stringResource(R.string.accounts_delete_blocked_text, removal.unsettledMessages, removal.drafts),
            confirm = R.string.accounts_delete_blocked_confirm,
            destructive = true,
            onDismiss = onCancelRemoval,
            onConfirm = { onRemove(true) },
        )
    }
}
