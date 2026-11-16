package app.dustbird.mail.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.ui.theme.accountAccent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun MessageHeader(state: HeaderState.Found, onSetSeen: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val summary = state.detail.summary
    var menuOpen by remember { mutableStateOf(false) }
    val pattern = stringResource(R.string.message_date_pattern)
    val date = remember(summary.sortDate, pattern) {
        DateTimeFormatter.ofPattern(pattern, Locale.KOREA).format(Instant.ofEpochMilli(summary.sortDate).atZone(ZoneId.systemDefault()))
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = summary.subject.ifBlank { stringResource(R.string.message_subject_empty) },
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.weight(1f).padding(top = 12.dp).semantics { heading() },
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.message_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (summary.seen) R.string.message_mark_unread else R.string.message_mark_read)) },
                        onClick = {
                            menuOpen = false
                            onSetSeen(!summary.seen)
                        },
                    )
                }
            }
        }
        Column {
            Text(
                text = summary.fromName ?: summary.fromAddress ?: stringResource(R.string.message_sender_unknown),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            if (summary.fromName != null && summary.fromAddress != null) {
                Text(summary.fromAddress, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Recipients(state.detail.to, state.detail.cc)
        if (state.showAccount && state.account != null) {
            val accent = accountAccent(state.accountIndex)
            val name = state.account.displayName
            val label = state.account.label
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Caption(stringResource(R.string.message_account))
                Surface(color = accent.container, contentColor = accent.content, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = if (name == label) name else stringResource(R.string.message_account_value, name, label),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (state.receivedIdentities.isEmpty()) {
            Text(stringResource(R.string.message_identity_unknown), style = MaterialTheme.typography.bodyMedium)
        } else {
            val lines = state.receivedIdentities.map { stringResource(R.string.message_identity_value, it.label, it.address) }
            CaptionedText(stringResource(R.string.message_identity), lines.joinToString("\n"))
        }
    }
}

@Composable
private fun Recipients(to: List<EmailAddress>, cc: List<EmailAddress>) {
    if (to.isEmpty() && cc.isEmpty()) {
        Text(stringResource(R.string.message_recipients_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val collapsible = to.size + cc.size > 3
    var expanded by rememberSaveable { mutableStateOf(false) }
    val collapsed = collapsible && !expanded
    Column {
        if (to.isNotEmpty()) RecipientLine(stringResource(R.string.message_recipients_to), to, collapsed)
        if (cc.isNotEmpty()) RecipientLine(stringResource(R.string.message_recipients_cc), cc, collapsed)
        if (collapsible) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.message_recipients_collapse else R.string.message_recipients_expand))
            }
        }
    }
}

@Composable
private fun RecipientLine(caption: String, list: List<EmailAddress>, collapsed: Boolean) {
    val shown = if (collapsed) list.take(2) else list
    val names = shown.joinToString(", ") { if (collapsed) it.name ?: it.address else it.name?.let { name -> "$name <${it.address}>" } ?: it.address }
    val text = if (collapsed && list.size > 2) stringResource(R.string.message_recipients_more, names, list.size - 2) else names
    CaptionedText(caption, text)
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CaptionedText(caption: String, value: String) {
    val captionColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = captionColor, fontWeight = FontWeight.SemiBold)) { append(caption) }
            append("  ")
            append(value)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}
