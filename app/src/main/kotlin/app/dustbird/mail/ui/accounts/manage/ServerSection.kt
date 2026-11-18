package app.dustbird.mail.ui.accounts.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.model.ConnectionStages
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.ui.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun ServerSection(account: MailAccount) {
    val container = LocalAppContainer.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable(account.id) { mutableStateOf(account.username) }
    var imapHost by rememberSaveable(account.id) { mutableStateOf(account.imap.host) }
    var imapPort by rememberSaveable(account.id) { mutableStateOf(account.imap.port.toString()) }
    var imapSecurity by rememberSaveable(account.id) { mutableStateOf(account.imap.security) }
    var smtpHost by rememberSaveable(account.id) { mutableStateOf(account.smtp.host) }
    var smtpPort by rememberSaveable(account.id) { mutableStateOf(account.smtp.port.toString()) }
    var smtpSecurity by rememberSaveable(account.id) { mutableStateOf(account.smtp.security) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }

    val imap = endpointOrNull(imapHost, imapPort, imapSecurity)
    val smtp = endpointOrNull(smtpHost, smtpPort, smtpSecurity)
    val valid = username.isNotBlank() && imap != null && smtp != null
    val changed = username.trim() != account.username || imap != account.imap || smtp != account.smtp
    val required = stringResource(R.string.accounts_field_required)
    val portInvalid = stringResource(R.string.accounts_port_invalid)
    val securities = TransportSecurity.entries.map { it to it.name }

    Section(R.string.accounts_server_heading) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
                onClickLabel = stringResource(if (expanded) R.string.accounts_server_hide else R.string.accounts_server_show),
                role = Role.Button,
            ) { expanded = !expanded },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (expanded) R.string.accounts_server_hide else R.string.accounts_server_show),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(if (expanded) painterResource(R.drawable.ic_keyboard_arrow_up) else painterResource(R.drawable.ic_keyboard_arrow_down), null)
        }
        if (expanded) {
            Text(stringResource(R.string.accounts_server_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FormField(R.string.accounts_username, username, { username = it; note = null }, error = required.takeIf { username.isBlank() })
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.accounts_imap_heading), style = MaterialTheme.typography.titleMedium)
                FormField(R.string.accounts_host, imapHost, { imapHost = it; note = null }, keyboardType = KeyboardType.Uri, error = required.takeIf { imapHost.isBlank() })
                FormField(R.string.accounts_port, imapPort, { imapPort = it; note = null }, keyboardType = KeyboardType.Number, error = portInvalid.takeIf { imapPort.toPort() == null })
                Text(stringResource(R.string.accounts_security), style = MaterialTheme.typography.labelLarge)
                ChoiceGroup(securities, imapSecurity, { imapSecurity = it; note = null })
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.accounts_smtp_heading), style = MaterialTheme.typography.titleMedium)
                FormField(R.string.accounts_host, smtpHost, { smtpHost = it; note = null }, keyboardType = KeyboardType.Uri, error = required.takeIf { smtpHost.isBlank() })
                FormField(R.string.accounts_port, smtpPort, { smtpPort = it; note = null }, keyboardType = KeyboardType.Number, error = portInvalid.takeIf { smtpPort.toPort() == null })
                Text(stringResource(R.string.accounts_security), style = MaterialTheme.typography.labelLarge)
                ChoiceGroup(securities, smtpSecurity, { smtpSecurity = it; note = null })
            }
            Text(stringResource(R.string.accounts_security_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                enabled = valid && changed,
                onClick = {
                    if (imap == null || smtp == null) return@Button
                    scope.launch {
                        val rejection = container.accounts.updateAccount(
                            accountId = account.id,
                            displayName = account.displayName,
                            label = account.label,
                            username = username,
                            imap = imap,
                            smtp = smtp,
                            syncMode = account.syncMode,
                            notificationsEnabled = account.notificationsEnabled,
                        )
                        if (rejection == null) container.accounts.updateStages(account.id) { ConnectionStages() }
                        note = resources.getString(rejection?.messageRes() ?: R.string.accounts_server_saved)
                    }
                },
            ) { Text(stringResource(R.string.accounts_server_save)) }
            note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun String.toPort(): Int? = trim().toIntOrNull()?.takeIf { it in 1..65535 }

private fun endpointOrNull(host: String, port: String, security: TransportSecurity): ServerEndpoint? {
    val number = port.toPort() ?: return null
    return if (host.isBlank()) null else ServerEndpoint(host.trim(), number, security)
}
