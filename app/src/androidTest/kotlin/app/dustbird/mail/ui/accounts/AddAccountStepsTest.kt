package app.dustbird.mail.ui.accounts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.R
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.ui.accounts.add.AddAccountFields
import app.dustbird.mail.ui.accounts.add.AssistState
import app.dustbird.mail.ui.accounts.add.FormStep
import app.dustbird.mail.ui.accounts.add.PrepareStep
import app.dustbird.mail.ui.accounts.add.rememberMotion
import app.dustbird.mail.ui.theme.DustbirdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AddAccountStepsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test
    fun preparationIsConfirmedWithACheckboxAndTheButtonStaysAtTheBottom() {
        var acknowledged by mutableStateOf(false)
        var next = 0
        compose.setContent {
            DustbirdTheme {
                PrepareStep(Providers.ICLOUD.id, acknowledged, rememberMotion(), { acknowledged = it }, { next++ })
            }
        }
        val confirm = compose.onNodeWithText(string(R.string.add_account_prepare_ack))
        val button = compose.onNodeWithText(string(R.string.add_account_next))

        confirm.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertIsOff()
        button.assertIsNotEnabled()
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("3").assertDoesNotExist()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(button.getUnclippedBoundsInRoot().bottom > root.bottom - 24.dp)
        assertTrue(confirm.getUnclippedBoundsInRoot().top > root.bottom - 160.dp)

        confirm.performClick()
        confirm.assertIsOn()
        button.assertIsEnabled().performClick()
        assertEquals(1, next)
    }

    @Test
    fun loginCheckStaysDisabledUntilEveryRequiredFieldHasText() {
        val fields = AddAccountFields().apply { reset(Providers.GMAIL, "Gmail") }
        var submitted = 0
        compose.setContent {
            DustbirdTheme {
                FormStep(Providers.GMAIL.id, fields, false, rememberMotion(), { submitted++ }, AssistState.Idle, false, {}, {}, {})
            }
        }
        val button = compose.onNodeWithText(string(R.string.add_account_form_verify))

        button.assertIsNotEnabled()
        compose.runOnUiThread {
            fields.address.setTextAndPlaceCursorAtEnd("not-an-address")
            fields.username.setTextAndPlaceCursorAtEnd("not-an-address")
        }
        button.assertIsNotEnabled()
        compose.runOnUiThread { fields.password.setTextAndPlaceCursorAtEnd("app-secret") }
        button.assertIsEnabled().performClick()
        assertEquals(1, submitted)

        compose.runOnUiThread { fields.label.setTextAndPlaceCursorAtEnd("  ") }
        button.assertIsNotEnabled()
    }

    @Test
    fun sentenceEntryOpensAgentSettingsWhenOffAndSendsTheTextWhenConnected() {
        var ready by mutableStateOf(false)
        var assist by mutableStateOf<AssistState>(AssistState.Idle)
        var settings = 0
        val sent = mutableListOf<String>()
        val fields = AddAccountFields()
        compose.setContent {
            DustbirdTheme {
                FormStep(Providers.GMAIL.id, fields, false, rememberMotion(), {}, assist, ready, { sent += it }, {}, { settings++ })
            }
        }
        compose.onNodeWithText(string(R.string.add_account_assist_off)).performClick()
        assertEquals(1, settings)

        ready = true
        compose.onNodeWithText(string(R.string.add_account_assist_hint)).performClick()
        compose.onNodeWithText(string(R.string.add_account_assist_privacy)).assertExists()
        compose.onNodeWithText(string(R.string.add_account_assist_run)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.add_account_assist_placeholder)).performTextInput("회사 메일 user@example.com")
        compose.onNodeWithText(string(R.string.add_account_assist_run)).performClick()
        assertEquals(listOf("회사 메일 user@example.com"), sent)
        assertEquals(1, settings)

        assist = AssistState.Done(listOf(R.string.add_account_field_address, R.string.add_account_section_imap))
        compose.onNodeWithText(string(R.string.add_account_assist_privacy)).assertDoesNotExist()
        compose.onNodeWithText(
            string(R.string.add_account_assist_done, string(R.string.add_account_field_address) + ", " + string(R.string.add_account_section_imap)),
        ).assertExists()
    }

    @Test
    fun aHiddenPasswordIsMaskedForAccessibilityAndAnICloudCustomDomainOpensTheLoginName() {
        val secret = "abcd-efgh-ijkl-mnop"
        val fields = AddAccountFields().apply { reset(Providers.ICLOUD, "iCloud") }
        compose.setContent {
            DustbirdTheme {
                FormStep(Providers.ICLOUD.id, fields, false, rememberMotion(), {}, AssistState.Idle, false, {}, {}, {})
            }
        }
        compose.runOnUiThread { fields.password.setTextAndPlaceCursorAtEnd(secret) }
        compose.waitForIdle()
        val password = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNode()
        assertEquals("\u2022".repeat(secret.length), password.config[SemanticsProperties.EditableText].text)
        compose.onAllNodes(hasText(secret, substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText(secret, substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.add_account_password_show)).performClick()
        compose.onAllNodes(hasText(secret)).assertCountEquals(1)

        assertFalse(fields.advancedOpen)
        compose.runOnUiThread { fields.address.setTextAndPlaceCursorAtEnd("user@example.test") }
        compose.onNodeWithText(string(R.string.add_account_icloud_custom_domain)).assertExists()
        compose.waitUntil(5_000) { fields.advancedOpen }
        compose.onNodeWithText(string(R.string.add_account_icloud_username_hint)).assertExists()
        compose.runOnUiThread { fields.address.setTextAndPlaceCursorAtEnd("user@icloud.com") }
        compose.onAllNodes(hasText(string(R.string.add_account_icloud_custom_domain))).assertCountEquals(0)
    }
}
