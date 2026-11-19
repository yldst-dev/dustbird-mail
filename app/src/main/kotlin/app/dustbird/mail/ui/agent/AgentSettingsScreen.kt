package app.dustbird.mail.ui.agent

import app.dustbird.mail.ui.theme.rememberHaptics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustbird.mail.R
import app.dustbird.mail.agent.AgentService
import app.dustbird.mail.agent.LoginFailure
import app.dustbird.mail.agent.LoginState
import app.dustbird.mail.data.AgentMode
import app.dustbird.mail.data.AgentSettings
import app.dustbird.mail.data.db.AgentAutomationEntity
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.accounts.manage.ChoiceGroup
import app.dustbird.mail.ui.accounts.manage.FormField
import app.dustbird.mail.ui.theme.FlatCard
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.CapsulePadding
import app.dustbird.mail.ui.theme.GroupTitle
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.cardItemShapes
import app.dustbird.mail.ui.theme.groupedTopAppBarColors
import app.dustbird.mail.ui.theme.pillFieldColors
import app.dustbird.mail.ui.theme.hiddenSecret
import kotlinx.coroutines.launch
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentSettingsRoute(onBack: () -> Unit) {
    val haptics = rememberHaptics()
    val container = LocalAppContainer.current
    val agent = container.agent
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by agent.current.collectAsStateWithLifecycle(AgentSettings())
    val accounts by container.accounts.accounts.collectAsStateWithLifecycle(emptyList())
    val memory by agent.memory.collectAsStateWithLifecycle(emptyList())
    val automations by agent.automations.collectAsStateWithLifecycle(emptyList())
    val log by agent.log.collectAsStateWithLifecycle(emptyList())
    val login by agent.login.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AgentAutomationEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = groupedTopAppBarColors(),
                title = { Text(stringResource(R.string.agent_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.agent_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(CapsuleGap),
        ) {
            GroupTitle(stringResource(R.string.agent_section_connection))
            FlatCard { ConnectionForm(settings, agent, login) }

            GroupTitle(stringResource(R.string.agent_section_privacy))
            SwitchItem(R.string.agent_consent_title, stringResource(R.string.agent_consent_text), settings.consent) { value ->
                scope.launch { agent.update { it.copy(consent = value) } }
            }
            SwitchItem(R.string.agent_auto_changes_title, stringResource(R.string.agent_auto_changes_text), settings.autoChanges) { value ->
                scope.launch { agent.update { it.copy(autoChanges = value) } }
            }
            SwitchItem(R.string.agent_inbox_digest_title, stringResource(R.string.agent_inbox_digest_text), settings.inboxDigest) { value ->
                scope.launch { agent.update { it.copy(inboxDigest = value) } }
            }
            if (accounts.size > 1) {
                FlatCard {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.agent_accounts_title), style = MaterialTheme.typography.titleSmallEmphasized, modifier = Modifier.padding(vertical = 4.dp))
                        accounts.forEach { account ->
                            val allowed = account.id !in settings.blockedAccounts
                            Row(
                                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                                    role = Role.Switch
                                    toggleableState = ToggleableState(allowed)
                                },
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Text(account.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Switch(
                                    checked = allowed,
                                    onCheckedChange = { value ->
                                        haptics.toggle(value)
                                        scope.launch { agent.update { it.copy(blockedAccounts = if (value) it.blockedAccounts - account.id else it.blockedAccounts + account.id) } }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            GroupTitle(stringResource(R.string.agent_section_instructions))
            FlatCard { InstructionsForm(settings.instructions) { text -> scope.launch { agent.update { it.copy(instructions = text) } } } }

            GroupTitle(stringResource(R.string.agent_section_memory))
            if (memory.isEmpty()) {
                FlatCard { Note(stringResource(R.string.agent_memory_empty)) }
            }
            memory.forEach { note ->
                ListItem(
                    shapes = cardItemShapes(),
                    contentPadding = CapsulePadding,
                    trailingContent = {
                        IconButton(onClick = { scope.launch { agent.deleteMemory(note.id) } }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.agent_memory_delete, note.text.take(20)))
                        }
                    },
                ) { Text(note.text) }
            }

            GroupTitle(stringResource(R.string.agent_section_automations))
            if (automations.isEmpty()) {
                FlatCard { Note(stringResource(R.string.agent_automations_empty)) }
            }
            automations.forEach { automation ->
                ListItem(
                    onClick = { editing = automation },
                    shapes = cardItemShapes(),
                    contentPadding = CapsulePadding,
                    supportingContent = { Text(triggerText(automation)) },
                    trailingContent = {
                        Switch(checked = automation.enabled, onCheckedChange = { value -> scope.launch { agent.saveAutomation(automation.copy(enabled = value)) } })
                    },
                ) { Text(automation.name) }
            }
            Button(
                onClick = { editing = AgentAutomationEntity(name = "", instruction = "", trigger = AgentService.TRIGGER_NEW_MAIL, hour = 8, allowChanges = false, enabled = true, lastRunAt = 0) },
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            ) { Text(stringResource(R.string.agent_automation_add)) }
            Text(
                stringResource(R.string.agent_automation_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            GroupTitle(stringResource(R.string.agent_section_log))
            FlatCard {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (log.isEmpty()) Text(stringResource(R.string.agent_log_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    log.take(30).forEach { entry ->
                        val outcome = stringResource(
                            when (entry.outcome) {
                                "DONE" -> R.string.agent_log_done
                                "DENIED" -> R.string.agent_log_denied
                                else -> R.string.agent_log_failed
                            },
                        )
                        val approval = stringResource(
                            when (entry.approval) {
                                "USER" -> R.string.agent_log_approval_user
                                "UNATTENDED" -> R.string.agent_log_approval_unattended
                                else -> R.string.agent_log_approval_auto
                            },
                        )
                        Column {
                            Text(stringResource(R.string.agent_log_line, stringResource(toolLabel(entry.tool)), outcome, approval), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                DateUtils.getRelativeTimeSpanString(entry.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { automation ->
        AutomationDialog(
            automation = automation,
            onSave = { saved ->
                editing = null
                scope.launch { agent.saveAutomation(saved) }
            },
            onDelete = {
                editing = null
                scope.launch { agent.deleteAutomation(automation.id) }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitchItem(title: Int, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    ListItem(
        onClick = { haptics.toggle(!checked); onChange(!checked) },
        shapes = cardItemShapes(),
        contentPadding = CapsulePadding,
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        supportingContent = { Text(text) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(stringResource(title)) }
}

@Composable
private fun triggerText(automation: AgentAutomationEntity): String =
    if (automation.trigger == AgentService.TRIGGER_DAILY) stringResource(R.string.agent_trigger_daily, automation.hour) else stringResource(R.string.agent_trigger_new_mail)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionForm(settings: AgentSettings, agent: AgentService, login: LoginState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable(settings.mode) { mutableStateOf(settings.mode) }
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    val secret = remember { TextFieldState() }
    var notice by remember { mutableStateOf<Int?>(null) }
    var saves by remember { mutableIntStateOf(0) }
    val account = remember(login, settings.mode) { agent.chatGptAccount() }
    val hasSecret = remember(notice, settings.mode) { agent.hasSecret() }
    val modes = listOf(
        AgentMode.OFF to stringResource(R.string.agent_mode_off),
        AgentMode.CHATGPT to stringResource(R.string.agent_mode_chatgpt),
        AgentMode.API_KEY to stringResource(R.string.agent_mode_api_key),
        AgentMode.GATEWAY to stringResource(R.string.agent_mode_gateway),
    )
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceGroup(modes, mode, { chosen ->
            mode = chosen
            notice = null
            if (chosen == AgentMode.OFF) scope.launch { agent.update { it.copy(mode = AgentMode.OFF) } }
            if (chosen == AgentMode.CHATGPT && account != null) scope.launch { agent.update { it.copy(mode = AgentMode.CHATGPT) } }
        })
        Text(
            stringResource(
                when (mode) {
                    AgentMode.OFF -> R.string.agent_mode_off_text
                    AgentMode.CHATGPT -> R.string.agent_mode_chatgpt_text
                    AgentMode.API_KEY -> R.string.agent_mode_api_key_text
                    AgentMode.GATEWAY -> R.string.agent_mode_gateway_text
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (mode) {
            AgentMode.OFF -> Unit
            AgentMode.CHATGPT -> {
                when (login) {
                    is LoginState.Code -> {
                        Text(stringResource(R.string.agent_chatgpt_code_title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        SelectionContainer {
                            Text(login.userCode, style = MaterialTheme.typography.headlineMediumEmphasized, fontFamily = FontFamily.Monospace)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = {
                                copyCode(context, login.userCode)
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, login.url.toUri())) }
                            }) { Text(stringResource(R.string.agent_chatgpt_open)) }
                            OutlinedButton(onClick = { copyCode(context, login.userCode) }) { Text(stringResource(R.string.agent_chatgpt_copy)) }
                            TextButton(onClick = agent::cancelLogin) { Text(stringResource(R.string.agent_chatgpt_cancel)) }
                        }
                        Text(stringResource(R.string.agent_chatgpt_code_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LoginState.Starting, LoginState.Working -> {
                        Text(stringResource(R.string.agent_chatgpt_working), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        OutlinedButton(onClick = agent::cancelLogin) { Text(stringResource(R.string.agent_chatgpt_cancel)) }
                    }
                    else -> if (account != null) {
                        Text(
                            text = if (account.isBlank()) stringResource(R.string.agent_chatgpt_signed_in_plain) else stringResource(R.string.agent_chatgpt_signed_in, account),
                            style = MaterialTheme.typography.bodyLargeEmphasized,
                        )
                        OutlinedButton(onClick = { scope.launch { agent.signOut() } }) { Text(stringResource(R.string.agent_chatgpt_logout)) }
                    } else {
                        Button(onClick = {
                            agent.acknowledgeLogin()
                            agent.startLogin()
                        }) { Text(stringResource(R.string.agent_chatgpt_login)) }
                    }
                }
                (login as? LoginState.Failed)?.let { failed ->
                    Text(
                        stringResource(
                            when (failed.failure) {
                                LoginFailure.EXPIRED -> R.string.agent_login_failed_expired
                                LoginFailure.NETWORK -> R.string.agent_login_failed_network
                                LoginFailure.REJECTED -> R.string.agent_login_failed_rejected
                            },
                        ),
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (account != null) ModelPicker(AgentMode.CHATGPT, settings.model, agent)
            }
            AgentMode.API_KEY, AgentMode.GATEWAY -> {
                val gateway = mode == AgentMode.GATEWAY
                FormField(
                    R.string.agent_field_base_url,
                    baseUrl,
                    { baseUrl = it },
                    keyboardType = KeyboardType.Uri,
                    support = stringResource(if (gateway) R.string.agent_field_base_url_hint_gateway else R.string.agent_field_base_url_hint_api),
                )
                SecureTextField(
                    state = secret,
                    label = { Text(stringResource(if (gateway) R.string.agent_field_token else R.string.agent_field_key)) },
                    supportingText = if (hasSecret) { { Text(stringResource(R.string.agent_field_secret_saved)) } } else null,
                    textObfuscationMode = TextObfuscationMode.Hidden,
                    modifier = Modifier.fillMaxWidth().hiddenSecret(secret.text.length),
                    shape = Pill,
                    colors = pillFieldColors(),
                )
                Button(
                    enabled = secret.text.isNotBlank() || hasSecret,
                    onClick = {
                        val address = baseUrl.trim()
                        if ((gateway || address.isNotEmpty()) && !AgentService.isSafeBase(address)) {
                            notice = R.string.agent_connection_invalid
                        } else {
                            val value = secret.text.toString()
                            scope.launch {
                                agent.saveConnection(mode, address, if (settings.mode == mode) settings.model else "", value)
                                secret.clearText()
                                notice = R.string.agent_connection_saved
                                saves++
                            }
                        }
                    },
                ) { Text(stringResource(R.string.agent_save_connection)) }
                notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (settings.mode == mode && hasSecret) ModelPicker(mode, settings.model, agent, refreshKey = saves)
            }
        }
    }
}

@Composable
private fun InstructionsForm(saved: String, onSave: (String) -> Unit) {
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField(R.string.agent_instructions_label, text, { text = it.take(2000) }, singleLine = false, support = stringResource(R.string.agent_instructions_hint))
        Button(enabled = text != saved, onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.agent_instructions_save)) }
    }
}

@Composable
private fun AutomationDialog(automation: AgentAutomationEntity, onSave: (AgentAutomationEntity) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(automation.name) }
    var instruction by remember { mutableStateOf(automation.instruction) }
    var trigger by remember { mutableStateOf(automation.trigger) }
    var hour by remember { mutableStateOf(automation.hour.toString()) }
    var allowChanges by remember { mutableStateOf(automation.allowChanges) }
    val parsedHour = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val valid = name.isNotBlank() && instruction.isNotBlank() && (trigger != AgentService.TRIGGER_DAILY || parsedHour != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_automation_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(R.string.agent_automation_name, name, { name = it.take(40) })
                FormField(R.string.agent_automation_instruction, instruction, { instruction = it.take(1000) }, singleLine = false)
                ChoiceGroup(
                    listOf(
                        AgentService.TRIGGER_NEW_MAIL to stringResource(R.string.agent_trigger_new_mail),
                        AgentService.TRIGGER_DAILY to stringResource(R.string.agent_trigger_daily_label),
                    ),
                    trigger,
                    { trigger = it },
                )
                if (trigger == AgentService.TRIGGER_DAILY) {
                    FormField(R.string.agent_automation_hour, hour, { hour = it.filter(Char::isDigit).take(2) }, keyboardType = KeyboardType.Number)
                }
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        role = Role.Switch
                        toggleableState = ToggleableState(allowChanges)
                    },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_automation_allow_changes), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.agent_automation_allow_changes_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = allowChanges, onCheckedChange = { allowChanges = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(automation.copy(name = name.trim(), instruction = instruction.trim(), trigger = trigger, hour = parsedHour ?: automation.hour, allowChanges = allowChanges)) },
            ) { Text(stringResource(R.string.agent_automation_save)) }
        },
        dismissButton = {
            Row {
                if (automation.id != 0L) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.agent_automation_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_automation_cancel)) }
            }
        },
    )
}

private fun copyCode(context: Context, code: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
}
