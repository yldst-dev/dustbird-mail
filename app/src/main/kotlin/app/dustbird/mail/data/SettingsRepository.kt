package app.dustbird.mail.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val defaultIdentity = longPreferencesKey("app_default_identity")
    private val hideLockScreen = booleanPreferencesKey("hide_lock_screen_content")
    private val batteryPromptShown = booleanPreferencesKey("battery_prompt_shown")

    val appDefaultIdentityId: Flow<Long?> = context.settingsStore.data.map { it[defaultIdentity] }
    val hideLockScreenContent: Flow<Boolean> = context.settingsStore.data.map { it[hideLockScreen] ?: true }
    val batteryPromptDone: Flow<Boolean> = context.settingsStore.data.map { it[batteryPromptShown] ?: false }

    suspend fun setAppDefaultIdentity(id: Long?) = edit { if (id == null) it.remove(defaultIdentity) else it[defaultIdentity] = id }

    suspend fun setHideLockScreenContent(value: Boolean) = edit { it[hideLockScreen] = value }

    suspend fun markBatteryPromptDone() = edit { it[batteryPromptShown] = true }

    suspend fun clearAll() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }
}
