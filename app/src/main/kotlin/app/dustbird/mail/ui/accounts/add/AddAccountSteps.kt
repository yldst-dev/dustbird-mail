@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.dustbird.mail.ui.accounts.add

import app.dustbird.mail.ui.theme.rememberHaptics
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.data.secure.SignInState
import app.dustbird.mail.ui.accounts.MicrosoftSignInPanel
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dustbird.mail.R
import app.dustbird.mail.core.agent.BackendFailure
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.CheckResult
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.sync.SyncOutcome
import app.dustbird.mail.ui.agent.AgentFailureMessage
import app.dustbird.mail.ui.agent.agentFailureText
import app.dustbird.mail.ui.mailbox.rememberAnimationsEnabled
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.CapsulePadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import app.dustbird.mail.ui.mailbox.AccountLabel
import app.dustbird.mail.ui.mailbox.IdentityLabel
import app.dustbird.mail.ui.theme.FlatCard
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.cardItemShapes
import app.dustbird.mail.ui.theme.hiddenSecret
import app.dustbird.mail.ui.theme.pillFieldColors
import app.dustbird.mail.ui.theme.pillToggleShapes

@Immutable
class Motion(private val scheme: MotionScheme, val enabled: Boolean) {
    fun <T> spatial(): FiniteAnimationSpec<T> = if (enabled) scheme.defaultSpatialSpec() else snap()
    fun <T> effects(): FiniteAnimationSpec<T> = if (enabled) scheme.defaultEffectsSpec() else snap()
}

@Composable
fun rememberMotion(): Motion {
    val scheme = MaterialTheme.motionScheme
    val enabled = rememberAnimationsEnabled()
    return remember(scheme, enabled) { Motion(scheme, enabled) }
}

private val wordWrap = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Normal, LineBreak.WordBreak.Phrase)

private fun TextStyle.prose(): TextStyle = copy(lineBreak = wordWrap, localeList = LocaleList("ko"))

@Composable
private fun StepColumn(actions: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val docked = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } >= 480.dp
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            if (!docked) actions?.invoke(this)
        }
        if (docked && actions != null) {
            HorizontalDivider(color = if (scroll.canScrollForward) MaterialTheme.colorScheme.outlineVariant else Color.Transparent)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
    ) { Text(text) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CapsuleGap)) {
            Providers.all.forEachIndexed { index, profile ->
                val texts = serviceTexts(profile.id)
                val name = stringResource(texts.name)
                ListItem(
                    onClick = { onChoose(profile.id, name) },
                    shapes = cardItemShapes(),
                    contentPadding = CapsulePadding,
                    supportingContent = { Text(stringResource(texts.hint)) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                ) { Text(name, style = MaterialTheme.typography.titleMediumEmphasized) }
            }
        }
    }
}

@Composable
private fun Notice(@DrawableRes icon: Int, text: String, color: Color) {
    FlatCard(color = color) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(icon), contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyLargeEmphasized.prose())
        }
    }
}

@Composable
fun PrepareStep(providerId: String, acknowledged: Boolean, motion: Motion, onAcknowledge: (Boolean) -> Unit, onNext: () -> Unit) {
    val haptics = rememberHaptics()
    val texts = serviceTexts(providerId)
    val steps = texts.checklist.filterNot { it in texts.notes }
    val marker = with(LocalDensity.current) { 28.sp.toDp() }
    StepColumn(
        actions = {
            val container by animateColorAsState(
                if (acknowledged) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                motion.effects(),
                label = "acknowledge",
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(Pill)
                    .background(container)
                    .toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = { haptics.toggle(it); onAcknowledge(it) })
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = acknowledged, onCheckedChange = null)
                Text(
                    stringResource(R.string.add_account_prepare_ack),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = if (acknowledged) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
            PrimaryButton(stringResource(R.string.add_account_next), onNext, enabled = acknowledged)
        },
    ) {
        if (providerId == Providers.CUSTOM.id) {
            Notice(R.drawable.ic_info, stringResource(R.string.add_account_prepare_custom_intro), MaterialTheme.colorScheme.secondaryContainer)
        } else if (providerId == Providers.OUTLOOK.id) {
            Notice(R.drawable.ic_info, stringResource(R.string.add_account_prepare_outlook_1), MaterialTheme.colorScheme.secondaryContainer)
        } else {
            Notice(
                R.drawable.ic_warning,
                stringResource(R.string.add_account_prepare_warning, stringResource(texts.name), stringResource(texts.secret)),
                MaterialTheme.colorScheme.errorContainer,
            )
        }
        FlatCard {
            Column(Modifier.padding(vertical = 10.dp)) {
                texts.checklist.forEach { item ->
                    val note = item in texts.notes
                    val style = if (note) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            Modifier.size(marker).background(if (note) Color.Transparent else MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (note) {
                                Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    (steps.indexOf(item) + 1).toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                        Text(
                            stringResource(item),
                            modifier = Modifier.weight(1f).padding(top = with(LocalDensity.current) { ((marker - style.lineHeight.toDp()) / 2).coerceAtLeast(0.dp) }),
                            style = style.prose(),
                            color = if (note) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

const val LabelPreviewTag = "add_account_label_preview"

private val labelPresets = listOf(R.string.add_account_label_personal, R.string.add_account_label_work, R.string.add_account_label_school)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelSetup(fields: AddAccountFields, accountIndex: Int, @StringRes labelError: Int?) {
    val haptics = rememberHaptics()
    val account = fields.label.text.trim().toString()
    val received = fields.identityLabel.text.trim().toString().ifEmpty { fields.address.text.trim().toString().substringBefore('@') }
    val empty = stringResource(R.string.add_account_labels_empty)
    Text(stringResource(R.string.add_account_labels_title), style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
    FlatCard {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.add_account_labels_preview), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                modifier = Modifier.testTag(LabelPreviewTag),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                AccountLabel(account.ifEmpty { empty }, accountIndex)
                IdentityLabel(stringResource(R.string.mailbox_row_received_at), received.ifEmpty { empty })
            }
        }
    }
    Field(fields.label, R.string.add_account_field_label, R.string.add_account_field_label_hint, labelError)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labelPresets.forEach { preset ->
            val text = stringResource(preset)
            FilterChip(
                selected = account == text,
                onClick = { haptics.tick(); fields.label.setTextAndPlaceCursorAtEnd(text) },
                label = { Text(text) },
                shape = Pill,
            )
        }
    }
    Field(fields.identityLabel, R.string.add_account_field_identity_label, R.string.add_account_field_identity_label_hint, null)
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
    TextField(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        shape = Pill,
        colors = pillFieldColors(),
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleButton(
                checked = selected == TransportSecurity.TLS,
                onCheckedChange = { onSelect(TransportSecurity.TLS) },
                shapes = pillToggleShapes(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.add_account_security_tls)) }
            ToggleButton(
                checked = selected == TransportSecurity.STARTTLS,
                onCheckedChange = { onSelect(TransportSecurity.STARTTLS) },
                shapes = pillToggleShapes(),
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
private fun AssistSheet(text: TextFieldState, @StringRes example: Int, assist: AssistState, onRun: () -> Unit, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val running = assist is AssistState.Running
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraExtraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.add_account_assist_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.add_account_assist_body),
                    style = MaterialTheme.typography.bodyMedium.prose(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextField(
                state = text,
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
                shape = MaterialTheme.shapes.large,
                colors = pillFieldColors(),
                placeholder = { Text(stringResource(example)) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
            )
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.add_account_assist_privacy),
                    style = MaterialTheme.typography.bodyMedium.prose(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                when (assist) {
                    AssistState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LoadingIndicator(Modifier.size(36.dp))
                        Text(stringResource(R.string.add_account_assist_running), style = MaterialTheme.typography.bodyMedium)
                    }
                    is AssistState.Failed -> AgentFailureMessage(assist.failure, assist.detail)
                    is AssistState.Done -> if (assist.filled.isEmpty()) {
                        Text(stringResource(R.string.add_account_assist_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    AssistState.Idle -> Unit
                }
            }
            PrimaryButton(stringResource(R.string.add_account_assist_run), onRun, enabled = !running && text.text.isNotBlank())
            if (assist is AssistState.Failed && (assist.failure == BackendFailure.NOT_CONFIGURED || assist.failure == BackendFailure.AUTH)) {
                TertiaryButton(stringResource(R.string.add_account_assist_settings), onOpenSettings)
            }
        }
    }
}

@Composable
fun FormStep(
    providerId: String,
    fields: AddAccountFields,
    showErrors: Boolean,
    motion: Motion,
    onSubmit: () -> Unit,
    assist: AssistState,
    agentReady: Boolean,
    onAssist: (String) -> Unit,
    onDismissAssist: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    signIn: SignInState = SignInState.Idle,
    clientId: TextFieldState? = null,
    onSignIn: () -> Unit = {},
    onCancelSignIn: () -> Unit = {},
    accountIndex: Int = 0,
) {
    val texts = serviceTexts(providerId)
    val tokenLogin = clientId != null && AuthMethod.OAUTH2 in Providers.byId(providerId).authMethods
    val invalid = if (showErrors) FormRules.invalidFields(fields.values()) else emptySet()
    var revealed by remember { mutableStateOf(false) }
    var assistOpen by rememberSaveable { mutableStateOf(false) }
    val description = rememberTextFieldState()
    val filled = (assist as? AssistState.Done)?.filled.orEmpty()
    val complete by remember(fields) { derivedStateOf { FormRules.isComplete(fields.values()) } }
    fun required(field: FormField) = if (field in invalid) R.string.add_account_error_required else null
    LaunchedEffect(assist) { if (filled.isNotEmpty()) assistOpen = false }
    if (assistOpen) {
        AssistSheet(
            text = description,
            example = if (providerId == Providers.CUSTOM.id) R.string.add_account_assist_placeholder_custom else R.string.add_account_assist_placeholder,
            assist = assist,
            onRun = { onAssist(description.text.toString()) },
            onOpenSettings = {
                assistOpen = false
                onDismissAssist()
                onOpenAgentSettings()
            },
            onDismiss = {
                assistOpen = false
                onDismissAssist()
            },
        )
    }
    StepColumn(actions = { PrimaryButton(stringResource(R.string.add_account_form_verify), onSubmit, enabled = complete) }) {
        val accent = MaterialTheme.colorScheme.onSecondaryContainer
        ListItem(
            onClick = { if (agentReady) assistOpen = true else onOpenAgentSettings() },
            shapes = cardItemShapes(),
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = accent,
                leadingContentColor = accent,
                trailingContentColor = accent,
                supportingContentColor = accent,
            ),
            contentPadding = CapsulePadding,
            leadingContent = { Icon(painterResource(R.drawable.ic_bird), contentDescription = null, modifier = Modifier.size(26.dp)) },
            trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
            supportingContent = {
                Text(
                    when {
                        filled.isNotEmpty() -> stringResource(R.string.add_account_assist_done, filled.map { stringResource(it) }.joinToString(", "))
                        agentReady -> stringResource(R.string.add_account_assist_hint)
                        else -> stringResource(R.string.add_account_assist_off)
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = LocalTextStyle.current.prose(),
                )
            },
        ) { Text(stringResource(R.string.add_account_assist_title), style = MaterialTheme.typography.titleMediumEmphasized) }
        val customDomain = Providers.isCustomICloudDomain(Providers.byId(providerId), fields.address.text.toString())
        LaunchedEffect(customDomain) { if (customDomain) fields.advancedOpen = true }
        Field(
            fields.address, R.string.add_account_field_address, R.string.add_account_icloud_custom_domain.takeIf { customDomain },
            if (FormField.ADDRESS in invalid) R.string.add_account_error_address else null,
            keyboardType = KeyboardType.Email,
        )
        val passwordMessage = required(FormField.PASSWORD)
            ?: R.string.add_account_password_hint.takeIf { providerId != Providers.CUSTOM.id }
        if (tokenLogin) MicrosoftSignInPanel(clientId!!, signIn, onSignIn, onCancelSignIn) else SecureTextField(
            state = fields.password,
            modifier = Modifier.fillMaxWidth().hiddenSecret(fields.password.text.length, hidden = !revealed),
            shape = Pill,
            colors = pillFieldColors(),
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
        Field(fields.identityName, R.string.add_account_field_identity_name, R.string.add_account_field_identity_name_hint, null)
        LabelSetup(fields, accountIndex, required(FormField.LABEL))
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
                Icon(painterResource(if (fields.advancedOpen) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down), contentDescription = null)
            }
            AnimatedVisibility(
                visible = fields.advancedOpen,
                enter = expandVertically(motion.spatial()) + fadeIn(motion.effects()),
                exit = shrinkVertically(motion.spatial()) + fadeOut(motion.effects()),
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Field(
                        fields.username, R.string.add_account_field_username,
                        if (customDomain) R.string.add_account_icloud_username_hint else R.string.add_account_field_username_hint,
                        required(FormField.USERNAME), keyboardType = KeyboardType.Email,
                    )
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
    }
}

@Composable
private fun FailureText(failure: ConnectFailure, detail: String?, providerId: String?, sameCause: Boolean = false) {
    Text(
        stringResource(failureTitle(failure)),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.error,
    )
    if (sameCause) {
        Text(stringResource(R.string.add_account_fail_same_cause), style = MaterialTheme.typography.bodyMedium)
    } else {
        failureBodies(failure, providerId).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    }
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
private fun StageRow(@StringRes title: Int, stage: Stage, providerId: String?, sameCauseAs: ConnectFailure? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                stage is Stage.Running -> LoadingIndicator(Modifier.size(40.dp))
                stage.passed -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                stage is Stage.Done -> Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        }
        Column(
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMediumEmphasized)
            val statusColor = MaterialTheme.colorScheme.onSurfaceVariant
            when (stage) {
                Stage.Pending -> Text(stringResource(R.string.add_account_verify_waiting), style = MaterialTheme.typography.bodyMedium, color = statusColor)
                Stage.Running -> Text(stringResource(R.string.add_account_verify_running), style = MaterialTheme.typography.bodyMedium, color = statusColor)
                is Stage.Done -> when (val result = stage.result) {
                    CheckResult.Ok -> Text(stringResource(R.string.add_account_verify_ok), style = MaterialTheme.typography.bodyMedium)
                    is CheckResult.Failed -> FailureText(result.failure, result.detail, providerId, sameCause = result.failure == sameCauseAs)
                }
            }
        }
    }
}

@Composable
fun VerifyStep(state: AddAccountUiState, onSave: () -> Unit, onEdit: () -> Unit, onRetry: () -> Unit) {
    val allPassed = state.imap.passed && state.smtp.passed
    val running = state.imap is Stage.Running || state.smtp is Stage.Running
    StepColumn(
        actions = {
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
        },
    ) {
        StageRow(R.string.add_account_section_imap, state.imap, state.providerId)
        HorizontalDivider()
        StageRow(
            R.string.add_account_section_smtp, state.smtp, state.providerId,
            sameCauseAs = ((state.imap as? Stage.Done)?.result as? CheckResult.Failed)?.failure,
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
    }
}

@Composable
fun SyncStep(state: AddAccountUiState, onRetry: () -> Unit, onContinue: () -> Unit, onSkip: () -> Unit) {
    val outcome = state.sync
    StepColumn(
        actions = when (outcome) {
            null -> null
            SyncOutcome.Synced -> {
                { PrimaryButton(stringResource(if (state.roles.isEmpty()) R.string.add_account_sync_finish else R.string.add_account_next), onContinue) }
            }
            else -> {
                {
                    PrimaryButton(stringResource(R.string.add_account_sync_retry), onRetry)
                    TertiaryButton(stringResource(R.string.add_account_sync_skip), onSkip)
                }
            }
        },
    ) {
        when (outcome) {
            null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoadingIndicator(Modifier.size(48.dp))
                Text(stringResource(R.string.add_account_sync_running), style = MaterialTheme.typography.bodyLarge)
            }
            SyncOutcome.Synced -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_account_sync_done_title), style = MaterialTheme.typography.titleLargeEmphasized)
                Text(stringResource(R.string.add_account_sync_done_body), style = MaterialTheme.typography.bodyLarge)
                if (state.roles.isNotEmpty()) {
                    Text(stringResource(R.string.add_account_sync_done_roles), style = MaterialTheme.typography.bodyLarge)
                }
            }
            SyncOutcome.NeedsReauth -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_account_sync_reauth_title), style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.add_account_sync_reauth_body), style = MaterialTheme.typography.bodyMedium)
            }
            is SyncOutcome.Failed -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_account_sync_failed_lead), style = MaterialTheme.typography.bodyLarge)
                FailureText(outcome.failure, null, state.providerId)
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
    StepColumn(actions = { PrimaryButton(stringResource(R.string.add_account_roles_done), onConfirm, enabled = !state.busy) }) {
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
    }
}
