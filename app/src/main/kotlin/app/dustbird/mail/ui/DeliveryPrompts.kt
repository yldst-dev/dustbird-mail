package app.dustbird.mail.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.dustbird.mail.R
import app.dustbird.mail.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object NotificationAccess {
    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    fun canAsk(granted: Boolean, askedBefore: Boolean, rationale: Boolean): Boolean = !granted && (!askedBefore || rationale)
}

object BatteryExemption {
    fun granted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
        } catch (_: ActivityNotFoundException) {
            openSettings(context)
        }
    }

    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

@Composable
fun rememberNotificationRequest(settings: SettingsRepository): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permission = Manifest.permission.POST_NOTIFICATIONS
                val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                val rationale = (context as? Activity)?.shouldShowRequestPermissionRationale(permission) == true
                if (NotificationAccess.canAsk(granted, settings.notificationPromptDone.first(), rationale)) {
                    settings.markNotificationPromptDone()
                    launcher.launch(permission)
                    return@launch
                }
            }
            NotificationAccess.openSettings(context)
        }
    }
}

@Composable
fun DeliveryPrompts(
    notifying: Boolean,
    realTime: Boolean,
    visible: Boolean,
    onAllowNotifications: () -> Unit,
    notificationsOn: (Context) -> Boolean = NotificationAccess::enabled,
    batteryExempt: (Context) -> Boolean = BatteryExemption::granted,
    onAllowBattery: (Context) -> Unit = BatteryExemption::request,
) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(true) }
    var exempt by remember { mutableStateOf(true) }
    var notifyLater by rememberSaveable(notifying) { mutableStateOf(false) }
    var batteryLater by rememberSaveable(realTime) { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        allowed = notificationsOn(context)
        exempt = batteryExempt(context)
        onPauseOrDispose { }
    }
    when {
        !visible -> Unit
        notifying && !allowed && !notifyLater ->
            PromptDialog(R.string.notify_prompt_title, R.string.notify_prompt_text, onAllowNotifications) { notifyLater = true }
        realTime && !exempt && !batteryLater ->
            PromptDialog(R.string.battery_prompt_title, R.string.battery_prompt_text, { onAllowBattery(context) }) { batteryLater = true }
    }
}

@Composable
private fun PromptDialog(@StringRes title: Int, @StringRes text: Int, onAllow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(title), style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading, localeList = LocaleList("ko"))) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.delivery_prompt_allow)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.delivery_prompt_later)) } },
    )
}
