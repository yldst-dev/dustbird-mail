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
import app.dustbird.mail.data.repo.DraftContent
import app.dustbird.mail.core.model.EmailAddress
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

    @Test
    fun scheduledMailShowsItsTimeAndOffersSendNowAndCancel() {
        val scheduled = outboxPresentation(SendState.QUEUED, null, heldUntil = "Nov 18, 9:00 AM")
        assertEquals(R.string.outbox_status_scheduled, scheduled.status)
        assertEquals("Nov 18, 9:00 AM", scheduled.detailArg)
        assertEquals(listOf(OutboxAction.SEND_NOW, OutboxAction.CANCEL_HOLD), scheduled.actions)
        assertEquals(emptyList(), outboxPresentation(SendState.QUEUED, null).actions)
    }

    @Test
    fun trackerShowsACountdownOnlyWhileTheMailCanStillBeCancelled() {
        val base = runBlocking {
            val account = mailbox.account("solo", "Solo")
            val identity = mailbox.identity(account, "me@example.test", "Main")
            mailbox.db.outbox().get(mailbox.outbox(account, identity, SendState.QUEUED, null))!!
        }
        val held = base.copy(sendAfter = 10_000, draftId = 7)
        assertEquals(TrackedPhase.WAITING, trackedPhase(held, now = 9_000))
        assertEquals(TrackedPhase.SENDING, trackedPhase(held, now = 10_000))
        assertEquals(TrackedPhase.SENDING, trackedPhase(held.copy(draftId = null), now = 9_000))
        assertEquals(TrackedPhase.SENT, trackedPhase(base.copy(state = SendState.SAVED), now = 0))
        assertEquals(TrackedPhase.COPY_FAILED, trackedPhase(base.copy(state = SendState.SAVE_FAILED), now = 0))
        assertEquals(TrackedPhase.FAILED, trackedPhase(base.copy(state = SendState.FAILED), now = 0))
    }

    @Test
    fun savedDraftsAreListedAndOpenForEditing() {
        val draftId = runBlocking {
            val account = mailbox.account("solo", "Solo")
            val identity = mailbox.identity(account, "me@example.test", "Main")
            mailbox.container.drafts.save(
                DraftContent(null, identity, listOf(EmailAddress("friend@example.test", "Friend")), emptyList(), emptyList(), "Unfinished subject", "Half a thought", null, emptyList()),
            )
        }
        var opened = -1L
        compose.setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides mailbox.container) { DraftsRoute(onBack = {}, onOpen = { opened = it }) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Unfinished subject", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Friend", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Unfinished subject", useUnmergedTree = true).performClick()
        assertEquals(draftId, opened)
    }
}
