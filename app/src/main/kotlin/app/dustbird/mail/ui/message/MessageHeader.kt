package app.dustbird.mail.ui.message

import app.dustbird.mail.ui.theme.rememberHaptics
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dustbird.mail.R
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.ui.mailbox.AccountLabel
import app.dustbird.mail.ui.mailbox.IdentityLabel
import app.dustbird.mail.ui.mailbox.rememberAnimationsEnabled
import app.dustbird.mail.ui.mailbox.rememberMessageTimeFormatter
import app.dustbird.mail.ui.theme.Capsule
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.FlatMenu
import app.dustbird.mail.ui.theme.MenuEntry
import app.dustbird.mail.ui.theme.capsuleShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val MessageAccountTag = "message_account_label"
const val MessageSenderTag = "message_sender"

class MessageActions(
    val onSetSeen: (Boolean) -> Unit,
    val onStar: (Boolean) -> Unit = {},
    val onArchive: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onMove: () -> Unit = {},
    val onSpam: (Boolean) -> Unit = {},
    val onAgent: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MessageHeader(state: HeaderState.Found, actions: MessageActions, modifier: Modifier = Modifier, side: Dp = 0.dp) {
    val haptics = rememberHaptics()
    val summary = state.detail.summary
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CapsuleGap)) {
        Row(Modifier.padding(start = side, end = (side - 12.dp).coerceAtLeast(0.dp)), verticalAlignment = Alignment.Top) {
            Text(
                text = summary.subject.ifBlank { stringResource(R.string.message_subject_empty) },
                style = MaterialTheme.typography.headlineSmallEmphasized.copy(lineBreak = LineBreak.Heading, localeList = LocaleList("ko")),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() },
            )
            FilledTonalIconToggleButton(
                checked = summary.flagged,
                onCheckedChange = { haptics.toggle(it); actions.onStar(it) },
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = scheme.onSurfaceVariant,
                    checkedContainerColor = scheme.tertiaryContainer,
                    checkedContentColor = scheme.onTertiaryContainer,
                ),
            ) {
                Icon(
                    painter = painterResource(if (summary.flagged) R.drawable.ic_star_filled else R.drawable.ic_star),
                    contentDescription = stringResource(R.string.message_star),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.message_more))
                }
                val inSpam = state.detail.folderRole == FolderRole.SPAM
                FlatMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    groups = listOf(
                        listOfNotNull(
                            MenuEntry(
                                if (summary.seen) R.string.message_mark_unread else R.string.message_mark_read,
                                if (summary.seen) R.drawable.ic_mark_email_unread else R.drawable.ic_mark_email_read,
                            ) { actions.onSetSeen(!summary.seen) },
                            MenuEntry(R.string.message_archive, R.drawable.ic_archive, actions.onArchive).takeIf { state.detail.folderRole == FolderRole.INBOX },
                            MenuEntry(R.string.message_delete, R.drawable.ic_delete, actions.onDelete),
                            MenuEntry(R.string.message_move, R.drawable.ic_folder, actions.onMove),
                            MenuEntry(
                                if (inSpam) R.string.message_not_spam else R.string.message_spam,
                                if (inSpam) R.drawable.ic_inbox else R.drawable.ic_report,
                            ) { actions.onSpam(!inSpam) },
                        ),
                        listOf(MenuEntry(R.string.message_ask_agent, R.drawable.ic_bird, actions.onAgent)),
                    ),
                )
            }
        }
        SenderCapsule(state, Modifier.padding(horizontal = side))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SenderCapsule(state: HeaderState.Found, modifier: Modifier = Modifier) {
    val summary = state.detail.summary
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var expanded by rememberSaveable { mutableStateOf(false) }
    val time = rememberMessageTimeFormatter().format(summary.sortDate)
    val lines = if (expanded) Int.MAX_VALUE else 1
    val resize = if (rememberAnimationsEnabled()) Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()) else Modifier
    Capsule(
        modifier.testTag(MessageSenderTag).clip(capsuleShape()).clickable(
            onClickLabel = stringResource(if (expanded) R.string.message_recipients_collapse else R.string.message_recipients_expand),
            onClick = { expanded = !expanded },
        ),
    ) {
        Column(resize.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SenderAvatar(summary.fromName ?: summary.fromAddress)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = summary.fromName ?: summary.fromAddress ?: stringResource(R.string.message_sender_unknown),
                        style = typography.titleMediumEmphasized,
                        maxLines = lines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (summary.fromName != null && summary.fromAddress != null) {
                        Text(summary.fromAddress, style = typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = lines, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(time, style = typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                    Icon(
                        painter = painterResource(if (expanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.offset(x = 6.dp),
                    )
                }
            }
            if (expanded) Details(state) else Delivery(state)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SenderAvatar(sender: String?) {
    val size = with(LocalDensity.current) { 48.sp.toDp() }.coerceIn(48.dp, 64.dp)
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = sender?.firstOrNull(Char::isLetterOrDigit)?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Delivery(state: HeaderState.Found) {
    val typography = MaterialTheme.typography
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.showAccount && state.account != null) AccountLabel(state.account.label, state.accountIndex, Modifier.testTag(MessageAccountTag))
        if (state.receivedIdentities.isEmpty()) {
            Text(stringResource(R.string.message_identity_unknown), style = typography.bodyMedium, color = muted)
        } else {
            IdentityLabel(stringResource(R.string.message_identity), state.receivedIdentities.joinToString(", ") { it.label })
        }
    }
}

@Composable
private fun Details(state: HeaderState.Found) {
    val detail = state.detail
    val typography = MaterialTheme.typography
    val pattern = stringResource(R.string.message_date_pattern)
    val date = remember(detail.summary.sortDate, pattern) {
        DateTimeFormatter.ofPattern(pattern, Locale.KOREA).format(Instant.ofEpochMilli(detail.summary.sortDate).atZone(ZoneId.systemDefault()))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (detail.to.isEmpty() && detail.cc.isEmpty()) {
            Text(stringResource(R.string.message_recipients_none), style = typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (detail.to.isNotEmpty()) DetailRow(R.string.message_recipients_to) { DetailText(addresses(detail.to)) }
        if (detail.cc.isNotEmpty()) DetailRow(R.string.message_recipients_cc) { DetailText(addresses(detail.cc)) }
        DetailRow(R.string.message_date) { DetailText(date) }
        if (state.showAccount && state.account != null) {
            DetailRow(R.string.message_account) {
                AccountLabel(state.account.label, state.accountIndex, Modifier.alignByBaseline())
                if (state.account.displayName != state.account.label) {
                    Text(state.account.displayName, style = typography.bodyMedium, modifier = Modifier.alignByBaseline().padding(start = 8.dp))
                }
            }
        }
        if (state.receivedIdentities.isEmpty()) {
            Text(stringResource(R.string.message_identity_unknown), style = typography.bodyMedium)
        } else {
            val lines = state.receivedIdentities.map { stringResource(R.string.message_identity_value, it.label, it.address) }
            DetailRow(R.string.message_identity) { DetailText(lines.joinToString("\n")) }
        }
    }
}

private fun addresses(list: List<EmailAddress>): String =
    list.joinToString(", ") { it.name?.let { name -> "$name <${it.address}>" } ?: it.address }

@Composable
private fun DetailRow(@StringRes caption: Int, value: @Composable RowScope.() -> Unit) {
    val style = MaterialTheme.typography.labelLarge
    val captionWidth = with(LocalDensity.current) { style.fontSize.toDp() } * 4.6f
    Row {
        Text(
            text = stringResource(caption),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(captionWidth).alignByBaseline(),
        )
        value()
    }
}

@Composable
private fun RowScope.DetailText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alignByBaseline())
}
