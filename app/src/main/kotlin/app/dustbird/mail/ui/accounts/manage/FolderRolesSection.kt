package app.dustbird.mail.ui.accounts.manage

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.folder.FolderInfo
import app.dustbird.mail.core.folder.FolderRoles
import app.dustbird.mail.core.folder.RoleResolution
import app.dustbird.mail.core.folder.RoleSource
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.theme.capsuleShape
import kotlinx.coroutines.launch

private val shownRoles = listOf(
    FolderRole.INBOX to R.string.accounts_role_inbox,
    FolderRole.SENT to R.string.accounts_role_sent,
    FolderRole.DRAFTS to R.string.accounts_role_drafts,
    FolderRole.SPAM to R.string.accounts_role_spam,
)

@Composable
fun FolderRolesSection(account: MailAccount, folders: List<FolderEntity>?) {
    var picking by rememberSaveable { mutableStateOf<FolderRole?>(null) }
    val infos = remember(folders) {
        folders.orEmpty().map { FolderInfo(it.path, it.delimiter?.firstOrNull(), it.attributes.split(' ').filter(String::isNotBlank).toSet()) }
    }
    val resolved = remember(folders) {
        FolderRoles.resolve(infos, folders.orEmpty().filter { it.roleConfirmed && it.role != null }.associate { it.role!! to it.path })
    }
    val automatic = remember(folders) { FolderRoles.resolve(infos, emptyMap()) }

    Section(R.string.accounts_folders_heading) {
        Text(stringResource(R.string.accounts_folders_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (folders != null && folders.isEmpty()) {
            Text(stringResource(R.string.accounts_folders_empty), style = MaterialTheme.typography.bodyMediumEmphasized)
        }
        if (!folders.isNullOrEmpty()) {
            shownRoles.forEachIndexed { index, (role, name) ->
                if (index > 0) HorizontalDivider()
                RoleRow(name, resolved.getValue(role), automatic.getValue(role), onPick = { picking = role })
            }
        }
    }

    picking?.let { role ->
        val (_, name) = shownRoles.first { it.first == role }
        FolderPicker(
            accountId = account.id,
            role = role,
            roleName = name,
            folders = folders.orEmpty().map { it.path },
            current = (resolved[role] as? RoleResolution.Assigned)?.path,
            candidates = (automatic[role] as? RoleResolution.NeedsConfirmation)?.candidates.orEmpty(),
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun RoleRow(@StringRes name: Int, resolution: RoleResolution, automatic: RoleResolution, onPick: () -> Unit) {
    val roleName = stringResource(name)
    val pickDescription = stringResource(R.string.accounts_role_pick_desc, roleName)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(roleName, style = MaterialTheme.typography.titleMedium)
        when (resolution) {
            is RoleResolution.Assigned -> {
                Text(stringResource(R.string.accounts_role_assigned, resolution.path), style = MaterialTheme.typography.bodyLargeEmphasized)
                Text(stringResource(sourceRes(resolution, automatic)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is RoleResolution.NeedsConfirmation -> {
                Text(stringResource(R.string.accounts_role_candidates, resolution.candidates.joinToString(", ")), style = MaterialTheme.typography.bodyLargeEmphasized)
                Text(stringResource(R.string.accounts_role_needs_confirmation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RoleResolution.Missing -> Text(stringResource(R.string.accounts_role_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onPick, modifier = Modifier.semantics { contentDescription = pickDescription }) {
            Text(stringResource(R.string.accounts_role_pick))
        }
    }
}

@StringRes
private fun sourceRes(assigned: RoleResolution.Assigned, automatic: RoleResolution): Int = when (assigned.source) {
    RoleSource.ATTRIBUTE -> R.string.accounts_role_source_server
    RoleSource.NAME_RULE -> R.string.accounts_role_source_inbox_name
    RoleSource.USER -> when (automatic) {
        is RoleResolution.Assigned -> if (automatic.path == assigned.path) sourceRes(automatic, automatic) else R.string.accounts_role_source_user
        is RoleResolution.NeedsConfirmation -> if (assigned.path in automatic.candidates) R.string.accounts_role_source_suggestion else R.string.accounts_role_source_user
        RoleResolution.Missing -> R.string.accounts_role_source_user
    }
}

@Composable
private fun FolderPicker(
    accountId: Long,
    role: FolderRole,
    @StringRes roleName: Int,
    folders: List<String>,
    current: String?,
    candidates: List<String>,
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val ordered = candidates + folders.filter { it !in candidates }
    AlertDialog(
        shape = capsuleShape(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accounts_role_pick_title, stringResource(roleName))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ordered.forEach { path ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = path == current,
                            role = Role.RadioButton,
                            onClick = {
                                scope.launch {
                                    container.sync.confirmRole(accountId, role, path)
                                    onDismiss()
                                }
                            },
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = path == current, onClick = null)
                        Text(
                            if (path in candidates) stringResource(R.string.accounts_role_pick_candidate, path) else path,
                            Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_cancel)) } },
    )
}
