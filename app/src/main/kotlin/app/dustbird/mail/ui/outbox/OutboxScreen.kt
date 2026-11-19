package app.dustbird.mail.ui.outbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.text.format.DateUtils
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustbird.mail.R
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.db.OutboxEntity
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.mailbox.AccountLabel
import app.dustbird.mail.ui.mailbox.IdentityLabel
import app.dustbird.mail.ui.mailbox.rememberAnimationsEnabled
import app.dustbird.mail.ui.mailbox.rememberMessageTimeFormatter
import app.dustbird.mail.ui.theme.FlatCard
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.FlatSnackbarHost
import app.dustbird.mail.ui.theme.groupedTopAppBarColors
import java.time.Instant

private data class Confirmation(val id: Long, val action: OutboxAction, val state: SendState)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OutboxRoute(onBack: () -> Unit, onEditDraft: (Long) -> Unit = {}) {
    val viewModel = appViewModel { OutboxViewModel(it) }
    val items by viewModel.items.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val identityLabels by viewModel.identityLabels.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val formatter = rememberMessageTimeFormatter()
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }

    LaunchedEffect(viewModel, resources) { viewModel.notices.collect { snackbar.showSnackbar(resources.getString(it)) } }
    LaunchedEffect(viewModel) { viewModel.edits.collect { onEditDraft(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = groupedTopAppBarColors(),
                title = { Text(stringResource(R.string.outbox_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.outbox_back))
                    }
                },
            )
        },
        snackbarHost = { FlatSnackbarHost(snackbar) },
    ) { padding ->
        val list = items
        when {
            list == null -> Centered(padding) {
                LoadingIndicator()
                Text(stringResource(R.string.outbox_loading), style = MaterialTheme.typography.bodyMedium)
            }
            list.isEmpty() -> Centered(padding) {
                Text(stringResource(R.string.outbox_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    text = stringResource(R.string.outbox_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                val now = remember(list) { Instant.now() }
                val indexed = remember(accounts) { accounts.withIndex().associateBy { it.value.id } }
                val animations = rememberAnimationsEnabled()
                val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(CapsuleGap),
                ) {
                    itemsIndexed(list, key = { _, item -> item.id }) { index, item ->
                        val account = indexed[item.accountId]
                        FlatCard(if (animations) Modifier.animateItem(effects, spatial, effects) else Modifier) {
                            OutboxRow(
                                item = item,
                                presentation = outboxPresentation(
                                    item.state,
                                    item.failure,
                                    heldUntil = item.sendAfter.takeIf { item.draftId != null && it > now.toEpochMilli() }?.let {
                                        DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                                    },
                                ),
                                time = formatter.format(item.createdAt, now),
                                accountLabel = if (accounts.size > 1) account?.value?.label else null,
                                accountIndex = account?.index ?: 0,
                                identityLabel = identityLabels[item.identityId],
                                busy = item.id in busy,
                                onAction = { action ->
                                    if (action == OutboxAction.RESEND || (action == OutboxAction.DISCARD && item.state != SendState.SAVED)) {
                                        confirmation = Confirmation(item.id, action, item.state)
                                    } else {
                                        viewModel.perform(item.id, action)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmation?.let { pending ->
        val resend = pending.action == OutboxAction.RESEND
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (resend) R.string.outbox_confirm_resend_title else R.string.outbox_confirm_discard_title)) },
            text = {
                Text(
                    stringResource(
                        when {
                            resend -> R.string.outbox_confirm_resend_body
                            pending.state == SendState.SAVE_FAILED -> R.string.outbox_confirm_discard_sent
                            else -> R.string.outbox_confirm_discard_unsent
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        viewModel.perform(pending.id, pending.action)
                    },
                ) { Text(stringResource(if (resend) R.string.outbox_confirm_resend_ok else R.string.outbox_confirm_discard_ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.outbox_confirm_cancel)) } },
        )
    }
}

@Composable
private fun Centered(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OutboxRow(
    item: OutboxEntity,
    presentation: OutboxPresentation,
    time: String,
    accountLabel: String?,
    accountIndex: Int,
    identityLabel: String?,
    busy: Boolean,
    onAction: (OutboxAction) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val working = stringResource(R.string.outbox_working)
    val detail = presentation.detailArg?.let { stringResource(presentation.detail, it) } ?: stringResource(presentation.detail)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(
                text = item.subject.ifBlank { stringResource(R.string.outbox_no_subject) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = time, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        Text(
            text = stringResource(R.string.outbox_recipients, item.recipientSummary),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (accountLabel != null || identityLabel != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (accountLabel != null) AccountLabel(accountLabel, accountIndex)
                if (identityLabel != null) IdentityLabel(stringResource(R.string.outbox_sender_identity), identityLabel)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy || presentation.inProgress) {
                LoadingIndicator(Modifier.size(32.dp).semantics { contentDescription = working })
            }
            Text(
                text = stringResource(presentation.status),
                style = MaterialTheme.typography.titleSmall,
                color = if (presentation.failed) scheme.error else scheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        if (presentation.actions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presentation.actions.forEachIndexed { index, action ->
                    if (index == 0) {
                        FilledTonalButton(onClick = { onAction(action) }, enabled = !busy) {
                            Text(stringResource(action.label))
                        }
                    } else {
                        OutlinedButton(onClick = { onAction(action) }, enabled = !busy) {
                            Text(stringResource(action.label))
                        }
                    }
                }
            }
        }
    }
}
