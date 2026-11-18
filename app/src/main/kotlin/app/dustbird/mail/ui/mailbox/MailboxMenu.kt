package app.dustbird.mail.ui.mailbox

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.folder.FolderRoles
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.ui.theme.Pill

private val RoleOrder = listOf(FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.ARCHIVE, FolderRole.SPAM, FolderRole.TRASH)

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

@DrawableRes
private fun folderIcon(role: FolderRole?): Int = when (role) {
    FolderRole.INBOX -> R.drawable.ic_inbox
    FolderRole.SENT -> R.drawable.ic_send
    FolderRole.DRAFTS -> R.drawable.ic_draft
    FolderRole.SPAM -> R.drawable.ic_report
    FolderRole.TRASH -> R.drawable.ic_delete
    FolderRole.ARCHIVE -> R.drawable.ic_archive
    FolderRole.ALL -> R.drawable.ic_stacks
    null -> R.drawable.ic_folder
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MailboxMenu(
    state: MailboxUiState,
    onSelect: (MailboxScope) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Set<Long> = emptySet(),
    onToggleAccount: (Long) -> Unit = {},
    drafts: Int = 0,
    onOpenDrafts: () -> Unit = {},
) {
    val multiple = state.accounts.size > 1
    val animations = rememberAnimationsEnabled()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(40.dp).clip(Pill))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        }
        if (multiple) {
            MenuItem(
                label = stringResource(R.string.mailbox_title_unified),
                icon = R.drawable.ic_stacks,
                selected = state.scope == MailboxScope.Unified,
                unread = state.unread.values.sum(),
                onClick = { onSelect(MailboxScope.Unified) },
            )
        }
        MenuItem(
            label = stringResource(R.string.drafts_title),
            icon = R.drawable.ic_draft,
            selected = false,
            unread = drafts,
            onClick = onOpenDrafts,
            count = R.string.drafts_count,
        )
        state.accounts.forEachIndexed { index, account ->
            val open = !multiple || account.id !in collapsed
            if (multiple) AccountHeader(account, index, open, state.unread[account.id] ?: 0) { onToggleAccount(account.id) }
            AnimatedVisibility(
                visible = open,
                enter = if (animations) expandVertically(spatial) + fadeIn(effects) else EnterTransition.None,
                exit = if (animations) shrinkVertically(spatial) + fadeOut(effects) else ExitTransition.None,
            ) {
                Column {
                    val folders = state.folders.filter { it.accountId == account.id }
                    RoleOrder.filter { role -> role == FolderRole.INBOX || folders.any { it.role == role } }.forEach { role ->
                        val own = MailboxScope.Account(account.id, role)
                        val target = if (!multiple && role == FolderRole.INBOX) MailboxScope.Unified else own
                        MenuItem(
                            label = stringResource(folderLabelRes(role)),
                            icon = folderIcon(role),
                            selected = state.scope == target || state.scope == own,
                            unread = if (role == FolderRole.INBOX) state.unread[account.id] ?: 0 else 0,
                            onClick = { onSelect(target) },
                        )
                    }
                    folders.firstOrNull { it.role == FolderRole.ALL }?.let { folder ->
                        val target = MailboxScope.Folder(account.id, folder.id)
                        MenuItem(
                            label = stringResource(R.string.mailbox_folder_all),
                            icon = folderIcon(FolderRole.ALL),
                            selected = state.scope == target,
                            unread = 0,
                            onClick = { onSelect(target) },
                        )
                    }
                    val others = folders.filter { it.role == null && !FolderRoles.isInbox(it.path) }
                    if (others.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.mailbox_folders_other),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                        )
                        others.forEach { folder ->
                            val target = MailboxScope.Folder(account.id, folder.id)
                            MenuItem(
                                label = folder.displayName(),
                                icon = folderIcon(null),
                                selected = state.scope == target,
                                unread = 0,
                                onClick = { onSelect(target) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountHeader(account: MailAccount, index: Int, open: Boolean, unread: Int, onToggle: () -> Unit) {
    val unreadText = stringResource(R.string.mailbox_unread_count, unread)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(Pill)
            .clickable(
                onClickLabel = stringResource(if (open) R.string.mailbox_account_collapse else R.string.mailbox_account_expand),
                role = Role.Button,
                onClick = onToggle,
            )
            .padding(start = 16.dp, end = 20.dp)
            .semantics { heading() },
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
        if (!open && unread > 0) {
            Text(unread.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { contentDescription = unreadText })
        }
        Icon(
            painter = painterResource(if (open) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MenuItem(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    unread: Int,
    onClick: () -> Unit,
    @StringRes count: Int = R.string.mailbox_unread_count,
) {
    val description = stringResource(count, unread)
    NavigationDrawerItem(
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(painterResource(icon), contentDescription = null) },
        selected = selected,
        onClick = onClick,
        badge = if (unread > 0) {
            { Text(unread.toString(), modifier = Modifier.semantics { contentDescription = description }) }
        } else {
            null
        },
    )
}
