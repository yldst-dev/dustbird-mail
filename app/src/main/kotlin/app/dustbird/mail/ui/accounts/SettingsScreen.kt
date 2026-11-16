package app.dustbird.mail.ui.accounts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val hideLockScreen = container.settings.hideLockScreenContent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val accounts = container.accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setHideLockScreen(value: Boolean) {
        viewModelScope.launch { container.settings.setHideLockScreenContent(value) }
    }

    fun resetEverything(done: () -> Unit) {
        viewModelScope.launch {
            container.maintenance.resetEverything()
            done()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val viewModel = appViewModel { SettingsViewModel(it) }
    val context = LocalContext.current
    val hideLockScreen by viewModel.hideLockScreen.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var confirmReset by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LifecycleResumeEffect(Unit) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        batteryExempt = isBatteryExempt(context)
        onPauseOrDispose { }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section(R.string.settings_section_notifications)
            Text(
                stringResource(if (notificationsOn) R.string.settings_notifications_allowed else R.string.settings_notifications_blocked),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!notificationsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                Button(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_notifications_allow))
                }
            }
            OutlinedButton(onClick = { openNotificationSettings(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_notifications_open))
            }
            SwitchRow(R.string.settings_hide_lock_title, R.string.settings_hide_lock_text, hideLockScreen, viewModel::setHideLockScreen)
            HorizontalDivider()

            Section(R.string.settings_section_sync)
            Text(stringResource(R.string.settings_sync_modes_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_sync_manual), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_sync_periodic), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_sync_idle), style = MaterialTheme.typography.bodyMedium)
            if (accounts.isEmpty()) {
                Text(stringResource(R.string.settings_sync_no_accounts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            accounts.forEach { account ->
                val mode = when (account.syncMode) {
                    SyncMode.MANUAL -> R.string.settings_mode_manual
                    SyncMode.PERIODIC -> R.string.settings_mode_periodic
                    SyncMode.IDLE -> R.string.settings_mode_idle
                }
                Text(
                    stringResource(R.string.settings_sync_account_mode, account.label.ifBlank { account.displayName }, stringResource(mode)),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(stringResource(R.string.settings_battery_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.settings_battery_text), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(if (batteryExempt) R.string.settings_battery_exempt else R.string.settings_battery_optimized),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { openBatterySettings(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_battery_open))
            }
            HorizontalDivider()

            Section(R.string.settings_section_privacy)
            Text(stringResource(R.string.settings_privacy_text), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { confirmReset = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.settings_reset_action)) }
            HorizontalDivider()

            Section(R.string.settings_section_about)
            TextButton(onClick = onOpenLicenses, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                stringResource(R.string.settings_version, versionName(context)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetEverything { (context as? Activity)?.recreate() }
                }) { Text(stringResource(R.string.settings_reset_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.settings_reset_cancel)) } },
        )
    }
}

@Composable
private fun Section(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SwitchRow(title: Int, text: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {
            role = Role.Switch
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

private fun openBatterySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun isBatteryExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun versionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
