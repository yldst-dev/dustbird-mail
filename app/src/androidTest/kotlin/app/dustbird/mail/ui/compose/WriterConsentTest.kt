package app.dustbird.mail.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.R
import app.dustbird.mail.ui.theme.DustbirdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class WriterConsentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var access by mutableStateOf(WriterAccess.CONSENT)
    private var agreed = 0
    private var settingsOpened = 0

    private fun text(id: Int) = context.getString(id)

    private fun show() = compose.setContent {
        DustbirdTheme {
            WriterSheet(
                writing = Writing.Idle,
                access = access,
                hasDraft = false,
                reply = false,
                subjectBlank = true,
                onRun = {},
                onApply = {},
                onOpenSettings = { settingsOpened++ },
                onDismiss = {},
                onConsent = { agreed++ },
            )
        }
    }

    @Test
    fun aConnectedAgentWithoutConsentAsksForItInPlaceInsteadOfSayingItIsNotConnected() {
        show()
        compose.onNodeWithText(text(R.string.agent_consent_needed)).assertExists()
        compose.onAllNodesWithText(text(R.string.compose_writer_off)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.agent_consent_agree)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.agent_consent_title)).performClick()
        compose.onNodeWithText(text(R.string.agent_consent_agree)).assertIsEnabled().performClick()
        assertEquals(1, agreed)
        assertEquals(0, settingsOpened)

        access = WriterAccess.READY
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.agent_consent_needed)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(text(R.string.compose_writer_run)).assertExists()
    }

    @Test
    fun anAgentThatIsNotConnectedStillPointsToTheSettings() {
        access = WriterAccess.OFF
        show()
        compose.onNodeWithText(text(R.string.compose_writer_off)).assertExists()
        compose.onNodeWithText(text(R.string.compose_writer_settings)).performClick()
        assertEquals(1, settingsOpened)
        assertEquals(0, agreed)
    }
}
