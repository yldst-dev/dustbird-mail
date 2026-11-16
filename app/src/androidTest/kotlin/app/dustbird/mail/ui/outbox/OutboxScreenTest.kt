package app.dustbird.mail.ui.outbox

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.R
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.mailbox.TestMailbox
import app.dustbird.mail.ui.theme.DustbirdTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class OutboxScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var mailbox: TestMailbox

    @Before
    fun setUp() {
        mailbox = TestMailbox()
    }

    @After
    fun tearDown() {
        mailbox.container.scope.cancel()
        mailbox.close()
    }

    private fun showWith(state: SendState, failure: String? = null) {
        runBlocking {
            val account = mailbox.account("solo", "Solo")
            val identity = mailbox.identity(account, "me@example.test", "Main")
            mailbox.outbox(account, identity, state, failure)
        }
        compose.setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides mailbox.container) { OutboxRoute(onBack = {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Outgoing subject").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertButtons(present: List<Int>, absent: List<Int>) {
        present.forEach { compose.onNodeWithText(mailbox.text(it)).assertExists() }
        absent.forEach { compose.onAllNodesWithText(mailbox.text(it)).assertCountEquals(0) }
    }

    @Test
    fun queuedItemOffersNoAction() {
        showWith(SendState.QUEUED)
        compose.onNodeWithText(mailbox.text(R.string.outbox_status_queued)).assertExists()
        assertButtons(
            present = emptyList(),
            absent = listOf(
                R.string.outbox_action_retry, R.string.outbox_action_discard, R.string.outbox_action_check_unknown,
                R.string.outbox_action_confirm_sent, R.string.outbox_action_resend, R.string.outbox_action_retry_save,
            ),
        )
    }

    @Test
    fun failedItemExplainsTheSenderRefusalAndOffersRetryAndDelete() {
        showWith(SendState.FAILED, "SENDER|550")
        compose.onNodeWithText(mailbox.text(R.string.outbox_status_failed)).assertExists()
        compose.onNodeWithText(mailbox.text(R.string.outbox_detail_failed_sender)).assertExists()
        assertButtons(
            present = listOf(R.string.outbox_action_retry, R.string.outbox_action_discard),
            absent = listOf(R.string.outbox_action_resend, R.string.outbox_action_check_unknown, R.string.outbox_action_retry_save),
        )
    }

    @Test
    fun serverFailureShowsTheServerCode() {
        showWith(SendState.FAILED, "SERVER|554")
        compose.onNodeWithText(mailbox.context.getString(R.string.outbox_detail_failed_server_code, "554")).assertExists()
    }

    @Test
    fun unknownItemExplainsNoAutomaticResendAndOffersThreeChoices() {
        showWith(SendState.UNKNOWN)
        compose.onNodeWithText(mailbox.text(R.string.outbox_detail_unknown)).assertExists()
        assertButtons(
            present = listOf(R.string.outbox_action_check_unknown, R.string.outbox_action_confirm_sent, R.string.outbox_action_resend),
            absent = listOf(R.string.outbox_action_retry, R.string.outbox_action_discard, R.string.outbox_action_retry_save),
        )
    }

    @Test
    fun resendAsksForConfirmationAndCancelKeepsTheItem() {
        showWith(SendState.UNKNOWN)
        compose.onNodeWithText(mailbox.text(R.string.outbox_action_resend)).performClick()
        compose.onNodeWithText(mailbox.text(R.string.outbox_confirm_resend_body)).assertExists()
        compose.onNodeWithText(mailbox.text(R.string.outbox_confirm_cancel)).performClick()
        compose.onAllNodesWithText(mailbox.text(R.string.outbox_confirm_resend_body)).assertCountEquals(0)
        compose.onNodeWithText(mailbox.text(R.string.outbox_status_unknown)).assertExists()
    }

    @Test
    fun saveFailedItemSaysTheMailWasSentAndOffersSaveRetry() {
        showWith(SendState.SAVE_FAILED, "SAVE_NO_FOLDER")
        compose.onNodeWithText(mailbox.text(R.string.outbox_status_save_failed)).assertExists()
        compose.onNodeWithText(mailbox.text(R.string.outbox_detail_save_no_folder)).assertExists()
        assertButtons(
            present = listOf(R.string.outbox_action_retry_save),
            absent = listOf(R.string.outbox_action_retry, R.string.outbox_action_resend, R.string.outbox_action_check_unknown),
        )
    }

    @Test
    fun savedItemOffersOnlyDelete() {
        showWith(SendState.SAVED)
        compose.onNodeWithText(mailbox.text(R.string.outbox_status_saved)).assertExists()
        assertButtons(
            present = listOf(R.string.outbox_action_discard),
            absent = listOf(R.string.outbox_action_retry, R.string.outbox_action_resend, R.string.outbox_action_retry_save),
        )
    }

    @Test
    fun presentationFallsBackForMissingOrUnknownCodes() {
        listOf(null, "", "MYSTERY", "SERVER|").forEach { failure ->
            val presentation = outboxPresentation(SendState.FAILED, failure)
            val expected = if (failure == "SERVER|") R.string.outbox_detail_failed_server else R.string.outbox_detail_failed_other
            assertEquals(expected, presentation.detail)
            assertEquals(listOf(OutboxAction.RETRY, OutboxAction.DISCARD), presentation.actions)
        }
        assertEquals(R.string.outbox_detail_save_error, outboxPresentation(SendState.SAVE_FAILED, null).detail)
        assertEquals(R.string.outbox_detail_queued_retry, outboxPresentation(SendState.QUEUED, "NETWORK").detail)
    }
}
