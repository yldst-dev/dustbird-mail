package app.dustbird.mail.ui.mailbox

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.data.repo.MailboxScope

private val NavigationFolders = listOf(FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.SPAM)

@StringRes
fun folderLabelRes(role: FolderRole): Int = when (role) {
    FolderRole.INBOX -> R.string.mailbox_folder_inbox
    FolderRole.SENT -> R.string.mailbox_folder_sent
    FolderRole.DRAFTS -> R.string.mailbox_folder_drafts
    FolderRole.SPAM -> R.string.mailbox_folder_spam
    FolderRole.TRASH -> R.string.mailbox_folder_trash
    FolderRole.ARCHIVE -> R.string.mailbox_folder_archive
    FolderRole.ALL -> R.string.mailbox_folder_all
}

@Composable
fun MailboxMenu(state: MailboxUiState, onSelect: (MailboxScope) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
        )
        if (state.accounts.size > 1) {
            MenuItem(
                label = stringResource(R.string.mailbox_title_unified),
                selected = state.scope == MailboxScope.Unified,
                unread = state.unread.values.sum(),
                onClick = { onSelect(MailboxScope.Unified) },
            )
            state.accounts.forEachIndexed { index, account ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp).semantics { heading() },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccountLabel(account.label, index)
                    Text(
                        text = account.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                NavigationFolders.forEach { role ->
                    val target = MailboxScope.Account(account.id, role)
                    MenuItem(
                        label = stringResource(folderLabelRes(role)),
                        selected = state.scope == target,
                        unread = if (role == FolderRole.INBOX) state.unread[account.id] ?: 0 else 0,
                        onClick = { onSelect(target) },
                    )
                }
            }
        } else {
            val only = state.accounts.firstOrNull()
            NavigationFolders.forEach { role ->
                val target = when {
                    role == FolderRole.INBOX -> MailboxScope.Unified
                    only != null -> MailboxScope.Account(only.id, role)
                    else -> return@forEach
                }
                val selected = state.scope == target || (role == FolderRole.INBOX && only != null && state.scope == MailboxScope.Account(only.id, role))
                MenuItem(
                    label = stringResource(folderLabelRes(role)),
                    selected = selected,
                    unread = if (role == FolderRole.INBOX) state.unread.values.sum() else 0,
                    onClick = { onSelect(target) },
                )
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, selected: Boolean, unread: Int, onClick: () -> Unit) {
    val description = stringResource(R.string.mailbox_unread_count, unread)
    NavigationDrawerItem(
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        badge = if (unread > 0) {
            { Text(unread.toString(), modifier = Modifier.semantics { contentDescription = description }) }
        } else {
            null
        },
    )
}
