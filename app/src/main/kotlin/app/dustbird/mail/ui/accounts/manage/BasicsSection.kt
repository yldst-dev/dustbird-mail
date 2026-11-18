package app.dustbird.mail.ui.accounts.manage

import app.dustbird.mail.ui.theme.rememberHaptics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.ui.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun BasicsSection(account: MailAccount) {
    val container = LocalAppContainer.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var displayName by rememberSaveable(account.id) { mutableStateOf(account.displayName) }
    var label by rememberSaveable(account.id) { mutableStateOf(account.label) }
    var syncMode by rememberSaveable(account.id) { mutableStateOf(account.syncMode) }
    var notifications by rememberSaveable(account.id) { mutableStateOf(account.notificationsEnabled) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }

    val valid = displayName.isNotBlank() && label.isNotBlank()
    val changed = displayName.trim() != account.displayName || label.trim() != account.label ||
        syncMode != account.syncMode || notifications != account.notificationsEnabled
    val modes = SyncMode.entries.map { it to stringResource(it.labelRes()) }
    val required = stringResource(R.string.accounts_field_required)

    Section(R.string.accounts_basics_heading) {
        FormField(
            R.string.accounts_display_name,
            displayName,
            { displayName = it; note = null },
            error = required.takeIf { displayName.isBlank() },
        )
        FormField(
            R.string.accounts_label,
            label,
            { label = it; note = null },
            error = required.takeIf { label.isBlank() },
            support = stringResource(R.string.accounts_label_support),
        )
        Text(stringResource(R.string.accounts_sync_mode), style = MaterialTheme.typography.titleMedium)
        ChoiceGroup(modes, syncMode, { syncMode = it; note = null })
        Text(stringResource(syncMode.descriptionRes()), style = MaterialTheme.typography.bodyMedium)
        if (account.providerId == Providers.NAVER.id) {
            Text(
                stringResource(R.string.accounts_sync_naver_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = notifications,
                role = Role.Switch,
                onValueChange = { enabled ->
                    haptics.toggle(enabled)
                    notifications = enabled
                    note = null
                    scope.launch {
                        container.accounts.updateAccount(
                            accountId = account.id,
                            displayName = account.displayName,
                            label = account.label,
                            username = account.username,
                            imap = account.imap,
                            smtp = account.smtp,
                            syncMode = account.syncMode,
                            notificationsEnabled = enabled,
                        )
                    }
                },
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.accounts_notifications), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = notifications, onCheckedChange = null)
        }
        Button(
            enabled = valid && changed,
            onClick = {
                scope.launch {
                    val rejection = container.accounts.updateAccount(
                        accountId = account.id,
                        displayName = displayName,
                        label = label,
                        username = account.username,
                        imap = account.imap,
                        smtp = account.smtp,
                        syncMode = syncMode,
                        notificationsEnabled = notifications,
                    )
                    note = resources.getString(rejection?.messageRes() ?: R.string.accounts_basics_saved)
                }
            },
        ) { Text(stringResource(R.string.accounts_basics_save)) }
        note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium) }
    }
}
