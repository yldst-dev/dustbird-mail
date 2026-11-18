package app.dustbird.mail.ui.message

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AppBarRow
import androidx.compose.material3.AppBarRowScope
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.mime.AttachmentInfo
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.ui.nav.ComposeMode
import app.dustbird.mail.ui.theme.capsuleShape
import java.util.Locale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.dustbird.mail.ui.theme.Capsule
import app.dustbird.mail.ui.theme.CapsulePadding
import app.dustbird.mail.ui.theme.capsuleItemShapes
import kotlin.math.roundToInt

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
internal fun ImageNotice(blocked: Int, unshown: Int, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    if (blocked <= 0 && unshown <= 0) return
    val allowed = blocked <= 0
    Capsule(modifier) {
        Row(
            Modifier.padding(start = 20.dp, top = 12.dp, end = if (allowed) 20.dp else 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_block), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (allowed) stringResource(R.string.message_images_unsafe, unshown) else stringResource(R.string.message_images_blocked, blocked),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!allowed) {
                    Text(
                        text = stringResource(R.string.message_images_blocked_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!allowed) {
                val description = stringResource(R.string.message_images_allow_description)
                FilledTonalButton(onClick = onAllow, modifier = Modifier.semantics { contentDescription = description }) {
                    Text(stringResource(R.string.message_images_allow))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSection(
    attachments: List<AttachmentInfo>,
    modifier: Modifier = Modifier,
    downloading: Set<Int> = emptySet(),
    onOpen: (Int) -> Unit = {},
    onSave: (Int) -> Unit = {},
) {
    if (attachments.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val unnamed = stringResource(R.string.message_attachment_unnamed)
    ListItem(
        onClick = { expanded = true },
        modifier = modifier,
        shapes = capsuleItemShapes(),
        contentPadding = CapsulePadding,
        leadingContent = { Icon(painterResource(R.drawable.ic_attach_file), contentDescription = null) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        supportingContent = { Text(attachments.joinToString(", ") { it.fileName ?: unnamed }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    ) { Text(stringResource(R.string.message_attachments, attachments.size)) }
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.extraExtraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                Text(
                    stringResource(R.string.message_attachments, attachments.size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp),
                )
                Text(
                    stringResource(R.string.message_attachments_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 12.dp),
                )
                attachments.forEachIndexed { index, attachment ->
                    AttachmentRow(attachment, index in downloading, onOpen = { onOpen(index) }, onSave = { onSave(index) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AttachmentRow(attachment: AttachmentInfo, busy: Boolean, onOpen: () -> Unit, onSave: () -> Unit) {
    val size = sizeText(attachment.size)
    val working = stringResource(R.string.message_attachment_downloading)
    Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = capsuleShape(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(attachment.fileName ?: stringResource(R.string.message_attachment_unnamed), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (size == null) attachment.mimeType else stringResource(R.string.message_attachment_meta, attachment.mimeType, size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                LoadingIndicator(Modifier.size(40.dp).semantics { contentDescription = working })
            } else {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.message_attachment_open)) }
                TextButton(onClick = onSave) { Text(stringResource(R.string.message_attachment_save)) }
            }
        }
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
internal fun BodySheet(
    body: BodyState,
    shape: Shape,
    bottomSpace: Dp,
    onRetry: () -> Unit,
    onLink: (LinkTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val page = (body as? BodyState.Ready)?.html?.let { html ->
        val themed = PageColors(scheme.surface.toArgb(), scheme.onSurface.toArgb(), scheme.primary.toArgb())
        val space = bottomSpace.value.roundToInt()
        remember(html, body.imagesAllowed, themed, space) { SafeHtml.page(html, body.imagesAllowed, themed, space) }
    }
    Surface(modifier, shape = shape, color = page?.let { Color(it.background) } ?: scheme.surface) {
        val clear = Modifier.padding(bottom = bottomSpace)
        Box(Modifier.padding(top = 12.dp)) {
            when (body) {
                BodyState.Loading -> CenteredLoading(clear)
                is BodyState.Failed -> CenteredMessage(stringResource(failureText(body.failure)), clear) {
                    Button(onClick = onRetry) { Text(stringResource(R.string.message_retry)) }
                }
                is BodyState.Ready -> when {
                    page != null -> SafeHtmlView(page, body.imagesAllowed, onLink, Modifier.fillMaxSize().testTag("message_body_html"))
                    body.text != null -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        val linkStyle = TextLinkStyles(SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline))
                        val latestOnLink by rememberUpdatedState(onLink)
                        val linked = remember(body.text, linkStyle) {
                            buildAnnotatedString {
                                append(body.text)
                                PlainLinks.find(body.text).forEach { span ->
                                    addLink(LinkAnnotation.Clickable(span.url, linkStyle) { latestOnLink(LinkTarget(span.url)) }, span.start, span.end)
                                }
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = linked,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp).then(clear),
                            )
                        }
                    }
                    else -> CenteredMessage(stringResource(R.string.message_body_empty), clear)
                }
            }
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
    Box(
        modifier.fillMaxWidth().padding(horizontal = FloatingToolbarDefaults.ScreenOffset, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalFloatingToolbar(expanded = true, expandedShadowElevation = 0.dp, collapsedShadowElevation = 0.dp) {
            AppBarRow(
                overflowIndicator = { menu ->
                    IconButton(onClick = { if (menu.isShowing) menu.dismiss() else menu.show() }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = more)
                    }
                },
            ) {
                action(reply, primary = true) { onAction(ComposeMode.REPLY) }
                if (showReplyAll) action(replyAll) { onAction(ComposeMode.REPLY_ALL) }
                action(forward) { onAction(ComposeMode.FORWARD) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AppBarRowScope.action(label: String, primary: Boolean = false, onClick: () -> Unit) = customItem(
    appbarContent = {
        if (primary) {
            Button(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(painterResource(R.drawable.ic_reply), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(label)
            }
        } else {
            TextButton(onClick = onClick) { Text(label) }
        }
    },
    menuContent = { menu ->
        DropdownMenuItem(
            text = { Text(label) },
            onClick = {
                menu.dismiss()
                onClick()
            },
        )
    },
)
