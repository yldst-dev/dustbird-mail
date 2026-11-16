package app.dustbird.mail.ui.message

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustbird.mail.R
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.nav.ComposeArgs

@Composable
fun MessageRoute(messageId: Long, onBack: () -> Unit, onCompose: (ComposeArgs) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.message_back))
                    }
                },
            )
        },
    ) { padding -> MessageContent(messageId, onCompose, Modifier.padding(padding)) }
}

@Composable
fun MessagePane(messageId: Long, onCompose: (ComposeArgs) -> Unit, modifier: Modifier = Modifier) {
    MessageContent(messageId, onCompose, modifier)
}

@Composable
private fun MessageContent(messageId: Long, onCompose: (ComposeArgs) -> Unit, modifier: Modifier) {
    key(messageId) {
        val viewModel = appViewModel(key = "message-$messageId") { MessageViewModel(it, messageId) }
        val header by viewModel.header.collectAsStateWithLifecycle()
        val body by viewModel.body.collectAsStateWithLifecycle()
        val replyAll by viewModel.replyAll.collectAsStateWithLifecycle()
        var pendingLink by remember { mutableStateOf<LinkTarget?>(null) }
        var markedRead by rememberSaveable { mutableStateOf(false) }
        val context = LocalContext.current

        val bodyReady = body is BodyState.Ready
        LaunchedEffect(bodyReady) {
            if (bodyReady && !markedRead) {
                markedRead = true
                viewModel.setSeen(true)
            }
        }

        when (val state = header) {
            HeaderState.Loading -> CenteredLoading(modifier)
            HeaderState.Missing -> CenteredMessage(stringResource(R.string.message_missing), modifier)
            is HeaderState.Found -> BoxWithConstraints(modifier.fillMaxSize()) {
                val headerMax = maxHeight * 0.45f
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.heightIn(max = headerMax).verticalScroll(rememberScrollState())) {
                        MessageHeader(state, onSetSeen = viewModel::setSeen)
                        (body as? BodyState.Ready)?.let { ready ->
                            ImageNotice(ready.blockedImages, ready.imagesAllowed, onAllow = viewModel::allowImages)
                            AttachmentSection(ready.attachments)
                        }
                    }
                    HorizontalDivider()
                    BodyArea(
                        body = body,
                        onRetry = viewModel::load,
                        onLink = { target ->
                            if (target.shownText == null || pendingLink?.url == target.url) pendingLink = target
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ActionBar(
                        showReplyAll = replyAll,
                        onAction = { onCompose(ComposeArgs(mode = it, sourceMessageId = messageId)) },
                    )
                }
            }
        }

        pendingLink?.let { target ->
            LinkDialog(
                target = target,
                onOpen = {
                    pendingLink = null
                    val intent = Intent(Intent.ACTION_VIEW, target.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.message_link_no_app, Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { pendingLink = null },
            )
        }
    }
}
