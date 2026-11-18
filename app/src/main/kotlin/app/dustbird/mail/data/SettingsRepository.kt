package app.dustbird.mail.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class AgentMode { OFF, CHATGPT, API_KEY, GATEWAY }

data class AgentSettings(
    val mode: AgentMode = AgentMode.OFF,
    val baseUrl: String = "",
    val model: String = "",
    val instructions: String = "",
    val autoChanges: Boolean = false,
    val consent: Boolean = false,
    val blockedAccounts: Set<Long> = emptySet(),
    val inboxDigest: Boolean = true,
) {
    val connected: Boolean get() = mode != AgentMode.OFF && consent
    val digestsOn: Boolean get() = connected && inboxDigest
}

class SettingsRepository(private val context: Context) {
    private val defaultIdentity = longPreferencesKey("app_default_identity")
    private val hideLockScreen = booleanPreferencesKey("hide_lock_screen_content")
    private val batteryPromptShown = booleanPreferencesKey("battery_prompt_shown")
    private val notificationPromptShown = booleanPreferencesKey("notification_prompt_shown")
    private val agentMode = stringPreferencesKey("agent_mode")
    private val agentBaseUrl = stringPreferencesKey("agent_base_url")
    private val agentModel = stringPreferencesKey("agent_model")
    private val agentInstructions = stringPreferencesKey("agent_instructions")
    private val agentAutoChanges = booleanPreferencesKey("agent_auto_changes")
    private val agentConsent = booleanPreferencesKey("agent_consent")
    private val agentBlockedAccounts = stringSetPreferencesKey("agent_blocked_accounts")
    private val agentInboxDigest = booleanPreferencesKey("agent_inbox_digest")
    private val agentCodexVersion = stringPreferencesKey("agent_codex_version")
    private val drawerCollapsed = stringSetPreferencesKey("drawer_collapsed_accounts")
    private val sendDelay = intPreferencesKey("send_delay_seconds")
    private val microsoftClient = stringPreferencesKey("microsoft_client_id")

    val appDefaultIdentityId: Flow<Long?> = context.settingsStore.data.map { it[defaultIdentity] }
    val hideLockScreenContent: Flow<Boolean> = context.settingsStore.data.map { it[hideLockScreen] ?: true }
    val batteryPromptDone: Flow<Boolean> = context.settingsStore.data.map { it[batteryPromptShown] ?: false }

    suspend fun setAppDefaultIdentity(id: Long?) = edit { if (id == null) it.remove(defaultIdentity) else it[defaultIdentity] = id }

    suspend fun setHideLockScreenContent(value: Boolean) = edit { it[hideLockScreen] = value }

    suspend fun markBatteryPromptDone() = edit { it[batteryPromptShown] = true }

    val notificationPromptDone: Flow<Boolean> = context.settingsStore.data.map { it[notificationPromptShown] ?: false }

    suspend fun markNotificationPromptDone() = edit { it[notificationPromptShown] = true }

    val microsoftClientId: Flow<String> = context.settingsStore.data.map { it[microsoftClient].orEmpty() }

    suspend fun setMicrosoftClientId(value: String) = edit { it[microsoftClient] = value.trim() }

    val sendDelaySeconds: Flow<Int> = context.settingsStore.data.map { (it[sendDelay] ?: DEFAULT_SEND_DELAY).coerceIn(0, MAX_SEND_DELAY) }

    suspend fun setSendDelaySeconds(value: Int) = edit { it[sendDelay] = value.coerceIn(0, MAX_SEND_DELAY) }

    val agent: Flow<AgentSettings> = context.settingsStore.data.map { agentOf(it) }

    private fun agentOf(prefs: Preferences) = AgentSettings(
        mode = prefs[agentMode]?.let { name -> AgentMode.entries.firstOrNull { it.name == name } } ?: AgentMode.OFF,
        baseUrl = prefs[agentBaseUrl].orEmpty(),
        model = prefs[agentModel].orEmpty(),
        instructions = prefs[agentInstructions].orEmpty(),
        autoChanges = prefs[agentAutoChanges] ?: false,
        consent = prefs[agentConsent] ?: false,
        blockedAccounts = prefs[agentBlockedAccounts].orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
        inboxDigest = prefs[agentInboxDigest] ?: true,
    )

    suspend fun updateAgent(transform: (AgentSettings) -> AgentSettings) = edit { prefs ->
        val next = transform(agentOf(prefs))
        prefs[agentMode] = next.mode.name
        prefs[agentBaseUrl] = next.baseUrl
        prefs[agentModel] = next.model
        prefs[agentInstructions] = next.instructions
        prefs[agentAutoChanges] = next.autoChanges
        prefs[agentConsent] = next.consent
        prefs[agentBlockedAccounts] = next.blockedAccounts.map { it.toString() }.toSet()
        prefs[agentInboxDigest] = next.inboxDigest
    }

    fun agentModels(mode: AgentMode): Flow<String> = context.settingsStore.data.map { it[modelsKey(mode)].orEmpty() }

    suspend fun setAgentModels(mode: AgentMode, value: String) = edit { it[modelsKey(mode)] = value }

    private fun modelsKey(mode: AgentMode) = stringPreferencesKey("agent_models_" + mode.name.lowercase())

    val codexVersion: Flow<String> = context.settingsStore.data.map { it[agentCodexVersion].orEmpty() }

    suspend fun setCodexVersion(value: String) = edit { it[agentCodexVersion] = value }

    val collapsedAccounts: Flow<Set<Long>> = context.settingsStore.data.map { prefs -> prefs[drawerCollapsed].orEmpty().mapNotNull { it.toLongOrNull() }.toSet() }

    suspend fun toggleCollapsedAccount(accountId: Long) = edit { prefs ->
        val key = accountId.toString()
        val current = prefs[drawerCollapsed].orEmpty()
        prefs[drawerCollapsed] = if (key in current) current - key else current + key
    }

    suspend fun clearAll() = edit { it.clear() }

    companion object {
        const val DEFAULT_SEND_DELAY = 5
        const val MAX_SEND_DELAY = 30
        val SEND_DELAY_CHOICES = listOf(0, 5, 10, 20, 30)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }
}
