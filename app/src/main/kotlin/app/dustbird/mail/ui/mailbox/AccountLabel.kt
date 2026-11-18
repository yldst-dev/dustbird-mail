package app.dustbird.mail.ui.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.accountAccent

@Composable
fun AccountLabel(label: String, index: Int, modifier: Modifier = Modifier) {
    val accent = accountAccent(index)
    Text(
        text = label,
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = accent.content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 160.dp)
            .background(accent.container, Pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun IdentityLabel(caption: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(
        modifier.border(1.dp, scheme.outlineVariant, Pill).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(caption, style = typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        Text(
            text = value,
            style = typography.labelMediumEmphasized,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
