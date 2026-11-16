package app.dustbird.mail.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.mime.AttachmentInfo
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.ui.nav.ComposeMode
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CenteredLoading(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.message_loading)
    Box(modifier.fillMaxSize().semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}

@Composable
internal fun CenteredMessage(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        action?.invoke()
    }
}

@Composable
internal fun ImageNotice(blocked: Int, allowed: Boolean, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    if (blocked <= 0) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(if (allowed) R.string.message_images_unsafe else R.string.message_images_blocked, blocked),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!allowed) FilledTonalButton(onClick = onAllow) { Text(stringResource(R.string.message_images_allow)) }
        }
    }
}

@Composable
internal fun AttachmentSection(attachments: List<AttachmentInfo>, modifier: Modifier = Modifier) {
    if (attachments.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.message_attachments, attachments.size), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.message_attachments_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.message_attachments_hide else R.string.message_attachments_show))
        }
        if (expanded) attachments.forEach { AttachmentRow(it) }
    }
}

@Composable
private fun AttachmentRow(attachment: AttachmentInfo) {
    val size = sizeText(attachment.size)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(attachment.fileName ?: stringResource(R.string.message_attachment_unnamed), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = if (size == null) attachment.mimeType else stringResource(R.string.message_attachment_meta, attachment.mimeType, size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun sizeText(bytes: Int): String? = when {
    bytes < 0 -> null
    bytes < 1024 -> stringResource(R.string.message_size_bytes, bytes.toString())
    bytes < 1024 * 1024 -> stringResource(R.string.message_size_kb, String.format(Locale.KOREA, "%.1f", bytes / 1024.0))
    else -> stringResource(R.string.message_size_mb, String.format(Locale.KOREA, "%.1f", bytes / (1024.0 * 1024.0)))
}

@Composable
internal fun BodyArea(body: BodyState, onRetry: () -> Unit, onLink: (LinkTarget) -> Unit, modifier: Modifier = Modifier) {
    when (body) {
        BodyState.Loading -> CenteredLoading(modifier)
        is BodyState.Failed -> CenteredMessage(stringResource(failureText(body.failure)), modifier) {
            Button(onClick = onRetry) { Text(stringResource(R.string.message_retry)) }
        }
        is BodyState.Ready -> when {
            body.html != null -> {
                val scheme = MaterialTheme.colorScheme
                val themed = PageColors(scheme.surface.toArgb(), scheme.onSurface.toArgb(), scheme.primary.toArgb())
                val page = remember(body.html, body.imagesAllowed, themed) { SafeHtml.page(body.html, body.imagesAllowed, themed) }
                SafeHtmlView(page, body.imagesAllowed, onLink, modifier.fillMaxSize().testTag("message_body_html"))
            }
            body.text != null -> Box(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(body.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
            }
            else -> CenteredMessage(stringResource(R.string.message_body_empty), modifier)
        }
    }
}

private fun failureText(failure: ConnectFailure?): Int = when (failure) {
    ConnectFailure.UNREACHABLE -> R.string.message_failure_network
    ConnectFailure.AUTH -> R.string.message_failure_auth
    ConnectFailure.TLS -> R.string.message_failure_tls
    ConnectFailure.SERVICE_DISABLED -> R.string.message_failure_service_disabled
    ConnectFailure.OTHER, null -> R.string.message_failure_other
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActionBar(showReplyAll: Boolean, onAction: (ComposeMode) -> Unit, modifier: Modifier = Modifier) {
    val reply = stringResource(R.string.message_reply)
    val replyAll = stringResource(R.string.message_reply_all)
    val forward = stringResource(R.string.message_forward)
    val more = stringResource(R.string.message_more_actions)
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        ButtonGroup(
            overflowIndicator = { menu ->
                FilledTonalIconButton(onClick = { if (menu.isShowing) menu.dismiss() else menu.show() }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = more)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            clickableItem(onClick = { onAction(ComposeMode.REPLY) }, label = reply)
            if (showReplyAll) clickableItem(onClick = { onAction(ComposeMode.REPLY_ALL) }, label = replyAll)
            clickableItem(onClick = { onAction(ComposeMode.FORWARD) }, label = forward)
        }
    }
}
