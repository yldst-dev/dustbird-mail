@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.dustbird.mail.ui.accounts.add

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.CheckResult
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.sync.SyncOutcome

@Immutable
class Motion(private val scheme: MotionScheme, val enabled: Boolean) {
    fun <T> spatial(): FiniteAnimationSpec<T> = if (enabled) scheme.defaultSpatialSpec() else snap()
    fun <T> effects(): FiniteAnimationSpec<T> = if (enabled) scheme.defaultEffectsSpec() else snap()
}

@Composable
fun rememberMotion(): Motion {
    val context = LocalContext.current
    val scheme = MaterialTheme.motionScheme
    return remember(scheme) {
        Motion(scheme, Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f)
    }
}

@Composable
private fun StepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
    ) { Text(text) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
    ) { Text(text) }
}

@Composable
private fun TertiaryButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

@Composable
fun ChooseStep(onChoose: (providerId: String, suggestedName: String) -> Unit) {
    StepColumn {
        Text(stringResource(R.string.add_account_choose_intro), style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Providers.all.forEach { profile ->
                val texts = serviceTexts(profile.id)
                val name = stringResource(texts.name)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clickable(role = Role.Button) { onChoose(profile.id, name) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, style = MaterialTheme.typography.titleLargeEmphasized)
                        Text(
                            stringResource(texts.hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun PrepareStep(providerId: String, acknowledged: Boolean, onAcknowledge: (Boolean) -> Unit, onNext: () -> Unit) {
    val texts = serviceTexts(providerId)
    StepColumn {
        if (providerId == Providers.CUSTOM.id) {
            Text(stringResource(R.string.add_account_prepare_custom_intro), style = MaterialTheme.typography.bodyLarge)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.add_account_prepare_warning, stringResource(texts.name), stringResource(texts.secret)),
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            texts.checklist.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .padding(top = 9.dp)
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                    )
                    Text(stringResource(item), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = onAcknowledge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = acknowledged, onCheckedChange = null)
            Text(stringResource(R.string.add_account_prepare_ack), style = MaterialTheme.typography.titleMediumEmphasized)
        }
        PrimaryButton(stringResource(R.string.add_account_next), onNext, enabled = acknowledged)
    }
}

@Composable
private fun Field(
    state: TextFieldState,
    @StringRes label: Int,
    @StringRes hint: Int?,
    @StringRes error: Int?,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    monospace: Boolean = false,
) {
    val message = error ?: hint
    OutlinedTextField(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        textStyle = if (monospace) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
        label = { Text(stringResource(label)) },
        supportingText = message?.let { { Text(stringResource(it)) } },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        lineLimits = TextFieldLineLimits.SingleLine,
    )
}

@Composable
private fun SecurityChoice(selected: TransportSecurity, onSelect: (TransportSecurity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.add_account_security), style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            ToggleButton(
                checked = selected == TransportSecurity.TLS,
                onCheckedChange = { onSelect(TransportSecurity.TLS) },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.add_account_security_tls)) }
            ToggleButton(
                checked = selected == TransportSecurity.STARTTLS,
                onCheckedChange = { onSelect(TransportSecurity.STARTTLS) },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.add_account_security_starttls)) }
        }
        Text(
            stringResource(R.string.add_account_security_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun FormStep(providerId: String, fields: AddAccountFields, showErrors: Boolean, motion: Motion, onSubmit: () -> Unit) {
    val texts = serviceTexts(providerId)
    val invalid = if (showErrors) FormRules.invalidFields(fields.values()) else emptySet()
    var revealed by remember { mutableStateOf(false) }
    fun required(field: FormField) = if (field in invalid) R.string.add_account_error_required else null
    StepColumn {
        Field(
            fields.address, R.string.add_account_field_address, null,
            if (FormField.ADDRESS in invalid) R.string.add_account_error_address else null,
            keyboardType = KeyboardType.Email,
        )
        val passwordMessage = required(FormField.PASSWORD)
            ?: R.string.add_account_password_hint.takeIf { providerId != Providers.CUSTOM.id }
        OutlinedSecureTextField(
            state = fields.password,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(texts.secret)) },
            supportingText = passwordMessage?.let { { Text(stringResource(it)) } },
            isError = FormField.PASSWORD in invalid,
            textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
            trailingIcon = {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(stringResource(if (revealed) R.string.add_account_password_hide else R.string.add_account_password_show))
                }
            },
        )
        Field(fields.displayName, R.string.add_account_field_display_name, R.string.add_account_field_display_name_hint, required(FormField.DISPLAY_NAME))
        Field(fields.label, R.string.add_account_field_label, R.string.add_account_field_label_hint, required(FormField.LABEL))
        Field(fields.identityName, R.string.add_account_field_identity_name, R.string.add_account_field_identity_name_hint, null)
        Column(Modifier.fillMaxWidth()) {
            val stateText = stringResource(
                if (fields.advancedOpen) R.string.add_account_advanced_expanded else R.string.add_account_advanced_collapsed,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { fields.advancedOpen = !fields.advancedOpen }
                    .semantics { stateDescription = stateText },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.add_account_advanced),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Icon(if (fields.advancedOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            AnimatedVisibility(
                visible = fields.advancedOpen,
                enter = expandVertically(motion.spatial()) + fadeIn(motion.effects()),
                exit = shrinkVertically(motion.spatial()) + fadeOut(motion.effects()),
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Field(fields.username, R.string.add_account_field_username, R.string.add_account_field_username_hint, required(FormField.USERNAME), keyboardType = KeyboardType.Email)
                    Text(stringResource(R.string.add_account_section_imap), style = MaterialTheme.typography.titleSmallEmphasized)
                    Field(
                        fields.imapHost, R.string.add_account_field_host, null,
                        if (FormField.IMAP_HOST in invalid) R.string.add_account_error_host else null,
                        keyboardType = KeyboardType.Uri, monospace = true,
                    )
                    Field(
                        fields.imapPort, R.string.add_account_field_port, null,
                        if (FormField.IMAP_PORT in invalid) R.string.add_account_error_port else null,
                        keyboardType = KeyboardType.Number,
                    )
                    SecurityChoice(fields.imapSecurity) { fields.imapSecurity = it }
                    Text(stringResource(R.string.add_account_section_smtp), style = MaterialTheme.typography.titleSmallEmphasized)
                    Field(
                        fields.smtpHost, R.string.add_account_field_host, null,
                        if (FormField.SMTP_HOST in invalid) R.string.add_account_error_host else null,
                        keyboardType = KeyboardType.Uri, monospace = true,
                    )
                    Field(
                        fields.smtpPort, R.string.add_account_field_port, null,
                        if (FormField.SMTP_PORT in invalid) R.string.add_account_error_port else null,
                        keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                    )
                    SecurityChoice(fields.smtpSecurity) { fields.smtpSecurity = it }
                }
            }
        }
        Text(
            stringResource(R.string.add_account_form_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(stringResource(R.string.add_account_form_verify), onSubmit)
    }
}

@Composable
private fun FailureText(failure: ConnectFailure, detail: String?, providerId: String?) {
    Text(
        stringResource(failureTitle(failure)),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.error,
    )
    failureBodies(failure, providerId).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    if (failure == ConnectFailure.OTHER && !detail.isNullOrBlank()) {
        Text(
            stringResource(R.string.add_account_fail_other_detail),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StageRow(@StringRes title: Int, stage: Stage, providerId: String?, skipped: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                stage is Stage.Running -> LoadingIndicator(Modifier.size(40.dp))
                stage.passed -> Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                stage is Stage.Done -> Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        }
        Column(
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMediumEmphasized)
            val statusColor = MaterialTheme.colorScheme.onSurfaceVariant
            when (stage) {
                Stage.Pending -> Text(
                    stringResource(if (skipped) R.string.add_account_verify_skipped else R.string.add_account_verify_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                )
                Stage.Running -> Text(stringResource(R.string.add_account_verify_running), style = MaterialTheme.typography.bodyMedium, color = statusColor)
                is Stage.Done -> when (val result = stage.result) {
                    CheckResult.Ok -> Text(stringResource(R.string.add_account_verify_ok), style = MaterialTheme.typography.bodyMedium)
                    is CheckResult.Failed -> FailureText(result.failure, result.detail, providerId)
                }
            }
        }
    }
}

@Composable
fun VerifyStep(state: AddAccountUiState, onSave: () -> Unit, onEdit: () -> Unit, onRetry: () -> Unit) {
    val allPassed = state.imap.passed && state.smtp.passed
    val running = state.imap is Stage.Running || state.smtp is Stage.Running
    StepColumn {
        StageRow(R.string.add_account_section_imap, state.imap, state.providerId, skipped = false)
        HorizontalDivider()
        StageRow(
            R.string.add_account_section_smtp, state.smtp, state.providerId,
            skipped = state.imap is Stage.Done && !state.imap.passed,
        )
        if (allPassed) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.add_account_verify_success_title), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(stringResource(R.string.add_account_verify_success_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.saveError?.let { error ->
            Text(
                stringResource(
                    when (error) {
                        is SaveError.Rejected -> rejectionText(error.reason)
                        SaveError.Storage -> R.string.add_account_save_storage
                    },
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoadingIndicator(Modifier.size(40.dp))
                Text(stringResource(R.string.add_account_saving), style = MaterialTheme.typography.bodyMedium)
            }
        }
        when {
            allPassed -> {
                PrimaryButton(stringResource(R.string.add_account_verify_save), onSave, enabled = !state.busy)
                TertiaryButton(stringResource(R.string.add_account_verify_edit), onEdit)
            }
            running -> TertiaryButton(stringResource(R.string.add_account_verify_edit), onEdit)
            else -> {
                PrimaryButton(stringResource(R.string.add_account_verify_edit), onEdit)
                SecondaryButton(stringResource(R.string.add_account_verify_retry), onRetry)
            }
        }
    }
}

@Composable
fun SyncStep(state: AddAccountUiState, onRetry: () -> Unit, onContinue: () -> Unit, onSkip: () -> Unit) {
    StepColumn {
        when (val outcome = state.sync) {
            null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoadingIndicator(Modifier.size(48.dp))
                Text(stringResource(R.string.add_account_sync_running), style = MaterialTheme.typography.bodyLarge)
            }
            SyncOutcome.Synced -> {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.add_account_sync_done_title), style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(stringResource(R.string.add_account_sync_done_body), style = MaterialTheme.typography.bodyLarge)
                    if (state.roles.isNotEmpty()) {
                        Text(stringResource(R.string.add_account_sync_done_roles), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                PrimaryButton(
                    stringResource(if (state.roles.isEmpty()) R.string.add_account_sync_finish else R.string.add_account_next),
                    onContinue,
                )
            }
            SyncOutcome.NeedsReauth -> {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.add_account_sync_reauth_title), style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.add_account_sync_reauth_body), style = MaterialTheme.typography.bodyMedium)
                }
                PrimaryButton(stringResource(R.string.add_account_sync_retry), onRetry)
                TertiaryButton(stringResource(R.string.add_account_sync_skip), onSkip)
            }
            is SyncOutcome.Failed -> {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.add_account_sync_failed_lead), style = MaterialTheme.typography.bodyLarge)
                    FailureText(outcome.failure, null, state.providerId)
                }
                PrimaryButton(stringResource(R.string.add_account_sync_retry), onRetry)
                TertiaryButton(stringResource(R.string.add_account_sync_skip), onSkip)
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!enabled) {
                Text(
                    stringResource(R.string.add_account_roles_taken),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun RolesStep(state: AddAccountUiState, onSelect: (FolderRole, String?) -> Unit, onConfirm: () -> Unit) {
    StepColumn {
        Text(stringResource(R.string.add_account_roles_intro), style = MaterialTheme.typography.bodyLarge)
        state.roles.forEach { prompt ->
            val taken = state.roles.filter { it.role != prompt.role }.mapNotNull { it.selected }.toSet()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider(Modifier.padding(bottom = 12.dp))
                Text(stringResource(roleName(prompt.role)), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    stringResource(if (prompt.missing) R.string.add_account_roles_missing else R.string.add_account_roles_candidates),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.selectableGroup()) {
                    OptionRow(stringResource(R.string.add_account_roles_none), prompt.selected == null, enabled = true) { onSelect(prompt.role, null) }
                    prompt.options.forEach { path ->
                        OptionRow(path, prompt.selected == path, enabled = path !in taken) { onSelect(prompt.role, path) }
                    }
                }
            }
        }
        PrimaryButton(stringResource(R.string.add_account_roles_done), onConfirm, enabled = !state.busy)
    }
}
