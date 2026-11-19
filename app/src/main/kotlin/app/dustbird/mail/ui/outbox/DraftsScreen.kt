package app.dustbird.mail.ui.outbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustbird.mail.R
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.DraftEntity
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.mailbox.rememberAnimationsEnabled
import app.dustbird.mail.ui.mailbox.rememberMessageTimeFormatter
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.CapsulePadding
import app.dustbird.mail.ui.theme.capsuleItemShapes
import app.dustbird.mail.ui.theme.groupedTopAppBarColors
import java.time.Instant
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DraftsRoute(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val drafts by remember(container) { container.drafts.drafts }.collectAsStateWithLifecycle(initialValue = null)
    val formatter = rememberMessageTimeFormatter()
    var deleting by remember { mutableStateOf<DraftEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = groupedTopAppBarColors(),
                title = { Text(stringResource(R.string.drafts_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.outbox_back))
                    }
                },
            )
        },
    ) { padding ->
        val list = drafts
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.drafts_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        stringResource(R.string.drafts_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> {
                val now = remember(list) { Instant.now() }
                val animations = rememberAnimationsEnabled()
                val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(CapsuleGap),
                ) {
                    items(list, key = { it.id }) { draft ->
                        val recipients = remember(draft.toAddresses) { Codec.parseAddresses(draft.toAddresses).joinToString(", ") { it.name ?: it.address } }
                        ListItem(
                            onClick = { onOpen(draft.id) },
                            shapes = capsuleItemShapes(),
                            contentPadding = CapsulePadding,
                            modifier = if (animations) Modifier.animateItem(effects, spatial, effects) else Modifier,
                            leadingContent = { Icon(painterResource(R.drawable.ic_draft), contentDescription = null) },
                            overlineContent = { Text(formatter.format(draft.updatedAt, now)) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        draft.subject.ifBlank { stringResource(R.string.mailbox_row_no_subject) },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (draft.body.isNotBlank()) {
                                        Text(draft.body.trim().replace('\n', ' '), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { deleting = draft }) {
                                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.drafts_delete))
                                }
                            },
                        ) {
                            Text(
                                recipients.ifBlank { stringResource(R.string.drafts_no_recipient) },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    deleting?.let { draft ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.drafts_delete_title)) },
            text = { Text(stringResource(R.string.drafts_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        container.scope.launch { container.drafts.delete(draft.id) }
                    },
                ) { Text(stringResource(R.string.drafts_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.outbox_confirm_cancel)) } },
        )
    }
}
