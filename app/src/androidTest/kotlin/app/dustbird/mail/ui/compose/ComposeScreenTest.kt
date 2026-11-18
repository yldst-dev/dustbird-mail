package app.dustbird.mail.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.R
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.theme.DustbirdTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class ComposeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = ComposeFixture()
    private lateinit var first: SeededIdentity
    private lateinit var second: SeededIdentity
    private var closed = false

    @Before
    fun setUp() {
        fixture.open()
        first = fixture.seedAccount("one")
        second = fixture.seedAccount("two")
        fixture.setDefaultIdentity(first.identityId)
        rule.setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides fixture.container) {
                    ComposeRoute(ComposeArgs()) { closed = true }
                }
            }
        }
        rule.waitUntil(5000) { rule.onAllNodesWithTagCount("compose_to") > 0 }
    }

    @After
    fun tearDown() = fixture.close()

    private fun ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun sendingWithoutRecipientsShowsTheErrorAndStaysOpen() {
        rule.onNodeWithTag("compose_send").performClick()
        rule.onNodeWithText(fixture.context.getString(R.string.compose_error_no_recipient)).assertIsDisplayed()
        assertFalse(closed)
        assertEquals(0, runBlocking { fixture.db.outbox().observeAll().first().size })
    }

    @Test
    fun theSendButtonOffersScheduledSending() {
        rule.onNodeWithTag("compose_schedule").performClick()
        rule.onNodeWithText(fixture.context.getString(R.string.compose_schedule_tomorrow_morning)).assertIsDisplayed()
        rule.onNodeWithText(fixture.context.getString(R.string.compose_schedule_pick)).performClick()
        rule.onNodeWithText(fixture.context.getString(R.string.compose_schedule_next)).assertIsDisplayed()
        assertFalse(closed)
        assertEquals(0, runBlocking { fixture.db.outbox().observeAll().first().size })
    }

    @Test
    fun anInvalidChipShowsItsErrorText() {
        val message = fixture.context.getString(R.string.compose_error_invalid_field)
        rule.onNodeWithTag("compose_to").performTextInput("broken,")
        rule.waitUntil(3000) { rule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun theSenderPickerListsEveryIdentityAndSwitchingUpdatesTheSenderRow() {
        rule.onNodeWithTag("compose_sender").assertTextContains(first.address, substring = true)
        rule.onNodeWithTag("compose_sender").performClick()
        rule.onNodeWithTag("compose_identity_${first.identityId}").assertIsDisplayed()
        rule.onNodeWithTag("compose_identity_${second.identityId}").assertIsDisplayed()
        rule.onNodeWithTag("compose_identity_${second.identityId}").performClick()
        rule.waitUntil(3000) { rule.onAllNodesWithTagCount("compose_identity_${second.identityId}") == 0 }
        rule.onNodeWithTag("compose_sender").assertTextContains(second.address, substring = true)
    }
}
