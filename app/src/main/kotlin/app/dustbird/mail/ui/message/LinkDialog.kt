package app.dustbird.mail.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R

@Composable
internal fun LinkDialog(target: LinkTarget, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val canOpen = LinkPolicy.canOpen(target.url)
    val shown = target.shownText?.takeIf { LinkPolicy.differs(it, target.url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (canOpen) R.string.message_link_title else R.string.message_link_refused_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!canOpen) Text(stringResource(R.string.message_link_refused), style = MaterialTheme.typography.bodyMedium)
                Column {
                    Text(stringResource(R.string.message_link_target), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(target.url, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
                if (shown != null) {
                    Column {
                        Text(stringResource(R.string.message_link_shown_as), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(shown, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (LinkPolicy.looksLikeAddress(shown)) {
                        Text(stringResource(R.string.message_link_mismatch), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (canOpen) TextButton(onClick = onOpen) { Text(stringResource(R.string.message_link_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(if (canOpen) R.string.message_link_cancel else R.string.message_link_close)) }
        },
    )
}
