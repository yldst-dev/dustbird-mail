package app.dustbird.mail.ui.accounts

import app.dustbird.mail.ui.theme.rememberHaptics
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.theme.FlatCard
import androidx.compose.material3.FilterChip
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.data.SettingsRepository
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.CapsulePadding
import app.dustbird.mail.ui.theme.GroupTitle
import app.dustbird.mail.ui.theme.cardItemShapes
import app.dustbird.mail.ui.theme.capsuleShape
import app.dustbird.mail.ui.theme.groupedTopAppBarColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val hideLockScreen = container.settings.hideLockScreenContent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val accounts = container.accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sendDelay = container.settings.sendDelaySeconds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_SEND_DELAY)

    fun setSendDelay(seconds: Int) {
        viewModelScope.launch { container.settings.setSendDelaySeconds(seconds) }
    }

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val haptics = rememberHaptics()
    val viewModel = appViewModel { SettingsViewModel(it) }
    val context = LocalContext.current
    val hideLockScreen by viewModel.hideLockScreen.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val sendDelay by viewModel.sendDelay.collectAsStateWithLifecycle()
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
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = groupedTopAppBarColors(),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(CapsuleGap),
        ) {
            GroupTitle(stringResource(R.string.settings_section_notifications))
            FlatCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(if (notificationsOn) R.string.settings_notifications_allowed else R.string.settings_notifications_blocked),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!notificationsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Button(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text(stringResource(R.string.settings_notifications_allow))
                        }
                    }
                    OutlinedButton(onClick = { openNotificationSettings(context) }) {
                        Text(stringResource(R.string.settings_notifications_open))
                    }
                }
            }
            ListItem(
                onClick = { haptics.toggle(!hideLockScreen); viewModel.setHideLockScreen(!hideLockScreen) },
                shapes = cardItemShapes(),
                contentPadding = CapsulePadding,
                modifier = Modifier.semantics {
                    role = Role.Switch
                    toggleableState = ToggleableState(hideLockScreen)
                },
                supportingContent = { Text(stringResource(R.string.settings_hide_lock_text)) },
                trailingContent = { Switch(checked = hideLockScreen, onCheckedChange = null) },
            ) { Text(stringResource(R.string.settings_hide_lock_title)) }

            GroupTitle(stringResource(R.string.settings_section_send))
            FlatCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_send_delay_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        stringResource(R.string.settings_send_delay_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsRepository.SEND_DELAY_CHOICES.forEach { seconds ->
                            FilterChip(
                                selected = sendDelay == seconds,
                                onClick = { haptics.tick(); viewModel.setSendDelay(seconds) },
                                label = {
                                    Text(if (seconds == 0) stringResource(R.string.settings_send_delay_off) else stringResource(R.string.settings_send_delay_seconds, seconds))
                                },
                                shape = Pill,
                            )
                        }
                    }
                }
            }

            GroupTitle(stringResource(R.string.settings_section_sync))
            FlatCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_sync_modes_title), style = MaterialTheme.typography.titleSmallEmphasized)
                    Text(stringResource(R.string.settings_sync_manual), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_sync_periodic), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_sync_idle), style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlatCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            style = MaterialTheme.typography.bodyLargeEmphasized,
                        )
                    }
                }
            }
            FlatCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_battery_title), style = MaterialTheme.typography.titleSmallEmphasized)
                    Text(stringResource(R.string.settings_battery_text), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(if (batteryExempt) R.string.settings_battery_exempt else R.string.settings_battery_optimized),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { openBatterySettings(context) }) {
                        Text(stringResource(R.string.settings_battery_open))
                    }
                }
            }

            GroupTitle(stringResource(R.string.settings_section_privacy))
            FlatCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_privacy_text), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { confirmReset = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(R.string.settings_reset_action)) }
                }
            }

            GroupTitle(stringResource(R.string.settings_section_about))
            ListItem(
                onClick = onOpenLicenses,
                shapes = cardItemShapes(),
                contentPadding = CapsulePadding,
                trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
            ) { Text(stringResource(R.string.settings_licenses)) }
            ListItem(shapes = cardItemShapes(), contentPadding = CapsulePadding) {
                Text(
                    stringResource(R.string.settings_version, versionName(context)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            shape = capsuleShape(),
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
