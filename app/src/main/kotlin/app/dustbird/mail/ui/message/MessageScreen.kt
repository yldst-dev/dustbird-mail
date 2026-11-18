package app.dustbird.mail.ui.message

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustbird.mail.R
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.nav.ComposePrefill
import app.dustbird.mail.core.identity.Mailto
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.capsuleShape
import app.dustbird.mail.ui.theme.groupedTopAppBarColors
import app.dustbird.mail.ui.mailbox.FolderPicker
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.data.repo.MoveTarget
import app.dustbird.mail.data.AttachmentFiles
import app.dustbird.mail.core.model.FolderRole
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageRoute(messageId: Long, onBack: () -> Unit, onCompose: (ComposeArgs) -> Unit, onOpenAgent: (Long?) -> Unit = {}) {
    val viewModel = appViewModel(key = "message-$messageId") { MessageViewModel(it, messageId) }
    val header by viewModel.header.collectAsStateWithLifecycle()
    val inInbox = (header as? HeaderState.Found)?.detail?.folderRole == FolderRole.INBOX
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = groupedTopAppBarColors(),
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.message_back))
                    }
                },
                actions = {
                    if (inInbox) {
                        IconButton(onClick = { viewModel.move(MoveTarget.Role(FolderRole.ARCHIVE)) }) {
                            Icon(painterResource(R.drawable.ic_archive), contentDescription = stringResource(R.string.message_archive))
                        }
                    }
                    if (header is HeaderState.Found) {
                        IconButton(onClick = viewModel::delete) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.message_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        MessageContent(
            messageId = messageId,
            onCompose = onCompose,
            modifier = Modifier.padding(start = padding.calculateStartPadding(direction), top = padding.calculateTopPadding(), end = padding.calculateEndPadding(direction)),
            onGone = onBack,
            onOpenAgent = onOpenAgent,
            docked = true,
            bottomInset = padding.calculateBottomPadding(),
        )
    }
}

@Composable
fun MessagePane(
    messageId: Long,
    onCompose: (ComposeArgs) -> Unit,
    modifier: Modifier = Modifier,
    onGone: () -> Unit = {},
    onOpenAgent: (Long?) -> Unit = {},
) {
    MessageContent(messageId, onCompose, modifier, onGone, onOpenAgent)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MessageContent(
    messageId: Long,
    onCompose: (ComposeArgs) -> Unit,
    modifier: Modifier,
    onGone: () -> Unit,
    onOpenAgent: (Long?) -> Unit,
    docked: Boolean = false,
    bottomInset: Dp = 0.dp,
) {
    key(messageId) {
        val viewModel = appViewModel(key = "message-$messageId") { MessageViewModel(it, messageId) }
        val header by viewModel.header.collectAsStateWithLifecycle()
        val body by viewModel.body.collectAsStateWithLifecycle()
        val replyAll by viewModel.replyAll.collectAsStateWithLifecycle()
        val downloading by viewModel.downloading.collectAsStateWithLifecycle()
        val folders by viewModel.folders.collectAsStateWithLifecycle()
        val confirmDelete by viewModel.confirmDelete.collectAsStateWithLifecycle()
        var pendingLink by remember { mutableStateOf<LinkTarget?>(null) }
        var markedRead by rememberSaveable { mutableStateOf(false) }
        var picking by remember { mutableStateOf(false) }
        var saving by rememberSaveable { mutableStateOf(-1) }
        val context = LocalContext.current
        val container = LocalAppContainer.current
        val gone by rememberUpdatedState(onGone)
        val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
            if (target != null && saving >= 0) viewModel.saveAttachment(saving, target, context.contentResolver)
            saving = -1
        }

        LaunchedEffect(viewModel) {
            for (event in viewModel.events) {
                when (event) {
                    MessageEvent.Gone -> gone()
                    is MessageEvent.Notice -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                    is MessageEvent.Open -> {
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(container.files.shareUri(event.file), container.files.viewType(event.info.fileName, event.info.mimeType))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.message_attachment_no_app, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

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
                val headerMax = maxOf(maxHeight * 0.45f, maxHeight - 200.dp)
                val actions = remember(viewModel) {
                    MessageActions(
                        onSetSeen = viewModel::setSeen,
                        onStar = viewModel::setFlagged,
                        onArchive = { viewModel.move(MoveTarget.Role(FolderRole.ARCHIVE)) },
                        onDelete = viewModel::delete,
                        onMove = { picking = true },
                        onSpam = { viewModel.move(MoveTarget.Role(if (it) FolderRole.SPAM else FolderRole.INBOX)) },
                        onAgent = { onOpenAgent(messageId) },
                    )
                }
                val side = if (docked) 16.dp else 0.dp
                val sheet = if (docked) MaterialTheme.shapes.extraExtraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize) else capsuleShape()
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.heightIn(max = headerMax).verticalScroll(rememberScrollState()).padding(bottom = CapsuleGap),
                        verticalArrangement = Arrangement.spacedBy(CapsuleGap),
                    ) {
                        MessageHeader(state, actions, side = side)
                        (body as? BodyState.Ready)?.let { ready ->
                            AttachmentSection(
                                attachments = ready.attachments,
                                modifier = Modifier.padding(horizontal = side),
                                downloading = downloading,
                                onOpen = viewModel::openAttachment,
                                onSave = { index ->
                                    saving = index
                                    saver.launch(AttachmentFiles.safeName(ready.attachments[index].fileName))
                                },
                            )
                            ImageNotice(ready.blockedImages, ready.unshownImages, onAllow = viewModel::allowImages, modifier = Modifier.padding(horizontal = side))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        BodySheet(
                            body = body,
                            shape = sheet,
                            bottomSpace = FloatingToolbarDefaults.ContainerSize + 16.dp + bottomInset,
                            onRetry = viewModel::load,
                            onLink = { target ->
                                val mail = if (LinkPolicy.schemeOf(target.url) == "mailto") Mailto.parse(target.url) else null
                                when {
                                    mail != null -> if (target.shownText == null) {
                                        container.prefill.value = ComposePrefill(mail.to, mail.cc, mail.bcc, mail.subject, mail.body)
                                        onCompose(ComposeArgs())
                                    }
                                    target.shownText == null || pendingLink?.url == target.url -> pendingLink = target
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        ActionBar(
                            showReplyAll = replyAll,
                            onAction = { onCompose(ComposeArgs(mode = it, sourceMessageId = messageId)) },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset),
                        )
                    }
                }
            }
        }

        if (picking) {
            FolderPicker(
                folders = folders,
                onPick = {
                    picking = false
                    viewModel.move(MoveTarget.Folder(it.id))
                },
                onDismiss = { picking = false },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                shape = capsuleShape(),
                onDismissRequest = { viewModel.answerDeleteConfirm(false) },
                title = { Text(stringResource(R.string.mailbox_delete_forever_title)) },
                text = { Text(stringResource(R.string.mailbox_delete_forever_body)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.answerDeleteConfirm(true) }) {
                        Text(stringResource(R.string.mailbox_delete_forever_ok), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { viewModel.answerDeleteConfirm(false) }) { Text(stringResource(R.string.mailbox_cancel)) } },
            )
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
