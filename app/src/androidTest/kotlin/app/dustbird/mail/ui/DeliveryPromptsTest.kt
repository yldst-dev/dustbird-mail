package app.dustbird.mail.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.R
import app.dustbird.mail.ui.theme.DustbirdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class DeliveryPromptsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var notificationsOn = true
    private var exempt = false
    private var notificationRequests = 0
    private var batteryRequests = 0
    private var notifying by mutableStateOf(true)
    private var realTime by mutableStateOf(true)
    private var visible by mutableStateOf(true)

    private fun text(id: Int) = context.getString(id)

    private fun show() = compose.setContent {
        DustbirdTheme {
            DeliveryPrompts(
                notifying = notifying,
                realTime = realTime,
                visible = visible,
                onAllowNotifications = { notificationRequests++ },
                notificationsOn = { notificationsOn },
                batteryExempt = { exempt },
                onAllowBattery = { batteryRequests++ },
            )
        }
    }

    private fun shown(title: Int) = compose.onAllNodesWithText(text(title)).fetchSemanticsNodes().isNotEmpty()
    private fun battery() = shown(R.string.battery_prompt_title)
    private fun notifications() = shown(R.string.notify_prompt_title)

    private fun leaveAndReturn() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun batteryIsAskedWhileRealTimeIsOnAndFollowsTheSystemSettingOnReturn() {
        show()
        compose.waitUntil(5_000) { battery() }
        compose.onNodeWithText(text(R.string.delivery_prompt_allow)).performClick()
        assertEquals(1, batteryRequests)
        exempt = true
        leaveAndReturn()
        compose.waitUntil(5_000) { !battery() }
        exempt = false
        leaveAndReturn()
        compose.waitUntil(5_000) { battery() }
    }

    @Test
    fun laterHidesItUntilRealTimeIsTurnedOnAgainAndItNeverShowsWhereItShouldNot() {
        show()
        compose.waitUntil(5_000) { battery() }
        compose.onNodeWithText(text(R.string.delivery_prompt_later)).performClick()
        compose.waitUntil(5_000) { !battery() }
        leaveAndReturn()
        assertFalse(battery())
        realTime = false
        compose.waitForIdle()
        realTime = true
        compose.waitUntil(5_000) { battery() }
        visible = false
        compose.waitUntil(5_000) { !battery() }
        assertEquals(0, batteryRequests)
    }

    @Test
    fun blockedNotificationsAreAskedFirstAndBatteryFollowsOnceTheyAreAllowed() {
        notificationsOn = false
        show()
        compose.waitUntil(5_000) { notifications() }
        assertFalse(battery())
        compose.onNodeWithText(text(R.string.delivery_prompt_allow)).performClick()
        assertEquals(1, notificationRequests)
        assertEquals(0, batteryRequests)
        notificationsOn = true
        leaveAndReturn()
        compose.waitUntil(5_000) { !notifications() && battery() }
    }

    @Test
    fun nothingIsAskedWhenNoAccountWantsNotificationsOrRealTime() {
        notificationsOn = false
        notifying = false
        realTime = false
        show()
        leaveAndReturn()
        assertFalse(notifications())
        assertFalse(battery())
        notifying = true
        compose.waitUntil(5_000) { notifications() }
        compose.onNodeWithText(text(R.string.delivery_prompt_later)).performClick()
        compose.waitUntil(5_000) { !notifications() }
    }

    @Test
    fun theSystemOffersTheDirectBatteryRequestForThisApp() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        assertNotNull(context.packageManager.resolveActivity(request, 0))
    }
}
