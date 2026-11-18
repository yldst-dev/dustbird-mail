package app.dustbird.mail.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustbird.mail.R
import app.dustbird.mail.agent.AgentService
import app.dustbird.mail.core.agent.AgentBackendException
import app.dustbird.mail.core.agent.BackendFailure
import app.dustbird.mail.data.AgentMode
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.CapsulePadding
import app.dustbird.mail.ui.theme.capsuleItemShapes
import app.dustbird.mail.ui.theme.cardItemShapes
import app.dustbird.mail.ui.theme.rememberHaptics
import kotlinx.coroutines.launch

const val ModelPickerTag = "agent_model_picker"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModelPicker(mode: AgentMode, selected: String, agent: AgentService, refreshKey: Any? = null) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val models by remember(mode) { agent.models(mode) }.collectAsStateWithLifecycle(emptyList())
    var syncing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<BackendFailure?>(null) }
    var open by rememberSaveable { mutableStateOf(false) }
    val sync = {
        if (!syncing) {
            syncing = true
            scope.launch {
                failure = try {
                    agent.syncModels(mode)
                    null
                } catch (error: AgentBackendException) {
                    error.failure
                }
                syncing = false
            }
        }
    }
    LaunchedEffect(mode, refreshKey) { sync() }
    val automaticSlug = agent.automaticModel(mode, models)
    val automatic = models.firstOrNull { it.slug == automaticSlug }?.name ?: automaticSlug
    val automaticKnown = mode != AgentMode.CHATGPT || models.isNotEmpty()
    val current = models.firstOrNull { it.slug == selected }
    val value = when {
        selected.isBlank() && automaticKnown -> stringResource(R.string.agent_model_auto_value, automatic)
        selected.isBlank() -> stringResource(R.string.agent_model_auto)
        else -> current?.name ?: selected
    }
    val automaticText = when {
        !automaticKnown -> stringResource(R.string.agent_model_auto_text_plain)
        mode == AgentMode.API_KEY -> stringResource(R.string.agent_model_auto_text_api, automatic)
        mode == AgentMode.GATEWAY -> stringResource(R.string.agent_model_auto_text_gateway, automatic)
        else -> stringResource(R.string.agent_model_auto_text, automatic)
    }
    val choose = { slug: String ->
        haptics.tick()
        open = false
        scope.launch { agent.update { it.copy(model = slug) } }
    }

    ListItem(
        onClick = { open = true },
        shapes = capsuleItemShapes(),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        contentPadding = CapsulePadding,
        modifier = Modifier.testTag(ModelPickerTag),
        overlineContent = { Text(stringResource(R.string.agent_field_model)) },
        trailingContent = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
    ) { Text(value) }

    if (!open) return
    ModalBottomSheet(
        onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraExtraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(CapsuleGap),
        ) {
            item {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.agent_model_sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(
                        stringResource(if (mode == AgentMode.CHATGPT) R.string.agent_model_sheet_body else R.string.agent_model_sheet_body_server),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SyncStatus(syncing, failure, empty = models.isEmpty(), onRetry = sync)
            }
            item {
                ModelRow(
                    name = stringResource(R.string.agent_model_auto),
                    description = automaticText,
                    checked = selected.isBlank(),
                    onClick = { choose("") },
                )
            }
            items(models, key = { it.slug }) { model ->
                ModelRow(model.name, model.description, model.slug == selected) { choose(model.slug) }
            }
            if (selected.isNotBlank() && current == null && models.isNotEmpty()) {
                item {
                    ModelRow(selected, stringResource(R.string.agent_model_missing), checked = true) { choose(selected) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncStatus(syncing: Boolean, failure: BackendFailure?, empty: Boolean, onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        when {
            syncing -> Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(32.dp))
                Text(stringResource(R.string.agent_model_syncing), style = MaterialTheme.typography.bodyMedium)
            }
            failure != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (empty) agentFailureText(failure) else R.string.agent_model_sync_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.agent_model_retry)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelRow(name: String, description: String, checked: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = checked,
        onClick = onClick,
        shapes = cardItemShapes(),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        contentPadding = CapsulePadding,
        supportingContent = if (description.isBlank()) null else { { Text(description) } },
        trailingContent = if (checked) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.menu_checked)) }
        } else {
            null
        },
    ) { Text(name, style = MaterialTheme.typography.titleMedium) }
}
