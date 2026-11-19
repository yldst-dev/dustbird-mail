package app.dustbird.mail.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.ui.agent.AgentConsentCard
import app.dustbird.mail.core.agent.BackendFailure
import app.dustbird.mail.ui.agent.AgentFailureMessage
import app.dustbird.mail.ui.agent.agentFailureText
import app.dustbird.mail.ui.theme.FlatCard
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.pillFieldColors
import app.dustbird.mail.ui.theme.rememberHaptics

enum class WriterAccess { READY, OFF, CONSENT, BLOCKED }

@Composable
fun WriterEntry(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(painterResource(R.drawable.ic_bird), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(stringResource(R.string.compose_writer_entry), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

private class Preset(@StringRes val label: Int, @StringRes val request: Int)

private val rewrites = listOf(
    Preset(R.string.compose_writer_preset_polish, R.string.compose_writer_request_polish),
    Preset(R.string.compose_writer_preset_shorter, R.string.compose_writer_request_shorter),
    Preset(R.string.compose_writer_preset_polite, R.string.compose_writer_request_polite),
    Preset(R.string.compose_writer_preset_spelling, R.string.compose_writer_request_spelling),
)

private val starters = listOf(Preset(R.string.compose_writer_preset_reply, R.string.compose_writer_request_reply))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun WriterSheet(
    writing: Writing,
    access: WriterAccess,
    hasDraft: Boolean,
    reply: Boolean,
    subjectBlank: Boolean,
    onRun: (String) -> Unit,
    onApply: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onConsent: () -> Unit = {},
) {
    val request = rememberTextFieldState()
    val haptics = rememberHaptics()
    val running = writing == Writing.Running
    val presets = if (hasDraft) rewrites else if (reply) starters else emptyList()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraExtraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_bird), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.compose_writer_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.compose_writer_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (access == WriterAccess.CONSENT) {
                AgentConsentCard(onConsent)
                return@Column
            }
            if (access != WriterAccess.READY) {
                FlatCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(if (access == WriterAccess.OFF) R.string.compose_writer_off else R.string.compose_writer_blocked),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (access == WriterAccess.OFF) Button(onClick = onOpenSettings) { Text(stringResource(R.string.compose_writer_settings)) }
                    }
                }
                return@Column
            }
            if (writing is Writing.Done) {
                Draft(writing.mail.takeIf { subjectBlank } ?: writing.mail.copy(subject = null), hasDraft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onRun(writing.request) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.compose_writer_again))
                    }
                    Button(
                        onClick = {
                            haptics.confirm()
                            onApply()
                        },
                        modifier = Modifier.weight(1.4f).heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.compose_writer_apply)) }
                }
                return@Column
            }
            if (presets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { preset ->
                        val text = stringResource(preset.request)
                        SuggestionChip(
                            onClick = {
                                haptics.tick()
                                onRun(text)
                            },
                            label = { Text(stringResource(preset.label)) },
                            enabled = !running,
                            shape = Pill,
                        )
                    }
                }
            }
            TextField(
                state = request,
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
                shape = MaterialTheme.shapes.large,
                colors = pillFieldColors(),
                placeholder = { Text(stringResource(if (reply) R.string.compose_writer_placeholder_reply else R.string.compose_writer_placeholder)) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
            )
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.compose_writer_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                when (writing) {
                    Writing.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LoadingIndicator(Modifier.size(36.dp))
                        Text(stringResource(R.string.compose_writer_running), style = MaterialTheme.typography.bodyMedium)
                    }
                    is Writing.Failed -> AgentFailureMessage(writing.failure, writing.detail)
                    else -> Unit
                }
            }
            Button(
                onClick = { onRun(request.text.toString()) },
                enabled = !running && request.text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.compose_writer_run)) }
            if (writing is Writing.Failed && (writing.failure == BackendFailure.NOT_CONFIGURED || writing.failure == BackendFailure.AUTH)) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.compose_writer_settings)) }
            }
        }
    }
}

@Composable
private fun Draft(mail: WrittenMail, replacing: Boolean) {
    FlatCard(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.compose_writer_result), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            mail.subject?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            SelectionContainer(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(mail.body, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    Text(
        stringResource(if (replacing) R.string.compose_writer_replace_note else R.string.compose_writer_insert_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
