package app.dustbird.mail.ui.mailbox

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.R
import kotlin.test.assertTrue
import app.dustbird.mail.ui.message.MessageSenderTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.db.ListState
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.theme.DustbirdTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class MailboxScreenTest {
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

    private fun show() {
        compose.setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides mailbox.container) {
                    MailboxRoute(onOpenMessage = {}, onCompose = {}, onOpenAccounts = {}, onOpenSettings = {}, onOpenOutbox = {})
                }
            }
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun unifiedListShowsMessagesFromTwoAccountsWithAccountLabels() {
        runBlocking {
            val personal = mailbox.account("personal", "Personal", sortOrder = 0)
            val work = mailbox.account("work", "Work", sortOrder = 1)
            mailbox.message(personal, mailbox.inbox(personal), uid = 1, subject = "Personal subject")
            mailbox.message(work, mailbox.inbox(work), uid = 2, subject = "Work subject", hasAttachment = true)
        }
        show()
        waitForText("Personal subject")
        waitForText("Work subject")
        compose.onAllNodesWithContentDescription(mailbox.text(R.string.mailbox_row_attachment_description), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag(AccountLabelTag, useUnmergedTree = true).assertCountEquals(2)
        compose.onNode(hasTestTag(AccountLabelTag) and hasText("Personal"), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag(AccountLabelTag) and hasText("Work"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun undoAfterSwipeDeleteRestoresTheRowOnceAndDoesNotDeleteItAgain() {
        val id = runBlocking {
            val solo = mailbox.account("solo", "Solo")
            mailbox.db.folders().insert(
                FolderEntity(
                    accountId = solo, path = "Trash", delimiter = "/", attributes = "", role = FolderRole.TRASH, roleConfirmed = true,
                    uidValidity = 1, highestUid = 0, baselineComplete = false, syncedAt = 0,
                ),
            )
            mailbox.message(solo, mailbox.inbox(solo), uid = 1, subject = "Swipe me")
        }
        val moved = mailbox.context.getString(R.string.mailbox_moved_trash, 1)
        fun rowGone() = compose.onAllNodesWithText("Swipe me", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        show()
        waitForText("Swipe me")
        compose.onNodeWithText("Swipe me").performTouchInput { swipeLeft() }
        waitForText(moved)
        compose.waitUntil(10_000) { rowGone() }
        compose.onNodeWithText(mailbox.text(R.string.mailbox_undo)).performClick()
        waitForText("Swipe me")
        assertFalse(runCatching { compose.waitUntil(2_000) { rowGone() } }.isSuccess)
        compose.onAllNodesWithText(moved).assertCountEquals(0)
        assertEquals(ListState.NORMAL, runBlocking { mailbox.db.messages().get(id)!!.listState })
    }

    @Test
    fun systemBackClosesTheOpenDrawerInsteadOfLeavingTheScreen() {
        runBlocking { mailbox.account("solo", "Solo") }
        show()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.mailbox_action_open_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.app_name)).assertIsNotDisplayed()
    }

    @Test
    fun moreMenuShowsItsActionsAndRunsThePickedOneOnce() {
        runBlocking {
            val solo = mailbox.account("solo", "Solo")
            mailbox.message(solo, mailbox.inbox(solo), uid = 1, subject = "Menu mail")
        }
        var accounts = 0
        compose.setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides mailbox.container) {
                    MailboxRoute(onOpenMessage = {}, onCompose = {}, onOpenAccounts = { accounts++ }, onOpenSettings = {}, onOpenOutbox = {})
                }
            }
        }
        waitForText("Menu mail")

        compose.onNodeWithContentDescription(mailbox.text(R.string.mailbox_action_more)).performClick()
        listOf(R.string.mailbox_action_refresh, R.string.mailbox_action_outbox, R.string.mailbox_action_accounts, R.string.mailbox_action_settings)
            .forEach { compose.onNodeWithText(mailbox.text(it)).assertIsDisplayed() }
        compose.onAllNodesWithText(mailbox.text(R.string.drafts_title)).assertCountEquals(2)

        compose.onNodeWithText(mailbox.text(R.string.mailbox_action_accounts)).performClick()
        compose.waitForIdle()
        assertEquals(1, accounts)
        compose.onNodeWithText(mailbox.text(R.string.mailbox_action_settings)).assertDoesNotExist()
    }

    @Test
    fun longPressSwitchesToTheSelectionBarAndClosingItBringsTheListBack() {
        runBlocking {
            val solo = mailbox.account("solo", "Solo")
            mailbox.message(solo, mailbox.inbox(solo), uid = 1, subject = "Pick me")
        }
        show()
        waitForText("Pick me")
        val write = mailbox.text(R.string.mailbox_compose)
        val close = mailbox.text(R.string.mailbox_selection_close)
        val count = mailbox.context.getString(R.string.mailbox_selection_count, 1)
        val picked = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        compose.onNodeWithContentDescription(write).assertExists()

        compose.onNodeWithText("Pick me").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(count).assertIsDisplayed()
        compose.onNodeWithContentDescription(write).assertDoesNotExist()
        compose.onNodeWithContentDescription(mailbox.text(R.string.mailbox_action_open_menu)).assertDoesNotExist()
        compose.onNode(picked and hasText("Pick me")).assertExists()

        compose.onNodeWithContentDescription(close).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(count).assertDoesNotExist()
        compose.onNodeWithContentDescription(close).assertDoesNotExist()
        compose.onNodeWithContentDescription(write).assertExists()
        compose.onNode(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected) and hasText("Pick me")).assertExists()
    }

    @Test
    fun accountSectionsInTheMenuCollapseOneByOneAndTheChoiceIsKept() {
        val work = runBlocking {
            mailbox.container.settings.clearAll()
            val work = mailbox.account("work", "Work", sortOrder = 0)
            val home = mailbox.account("home", "Home", sortOrder = 1)
            mailbox.inbox(work)
            mailbox.inbox(home)
            work
        }
        val inbox = mailbox.text(R.string.mailbox_folder_inbox)
        fun inboxRows() = compose.onAllNodesWithText(inbox).fetchSemanticsNodes().size
        fun kept() = runBlocking { mailbox.container.settings.collapsedAccounts.first() }
        show()
        compose.onNodeWithContentDescription(mailbox.text(R.string.mailbox_action_open_menu)).performClick()
        compose.waitUntil(10_000) { inboxRows() == 2 }
        compose.onNodeWithText("work@example.test").performClick()
        compose.waitUntil(10_000) { inboxRows() == 1 }
        compose.waitUntil(10_000) { kept() == setOf(work) }
        compose.onNodeWithText("home@example.test").assertIsDisplayed()
        compose.onNodeWithText("work@example.test").performClick()
        compose.waitUntil(10_000) { inboxRows() == 2 }
        compose.waitUntil(10_000) { kept().isEmpty() }
    }

    @Test
    fun newMailArrivingWhileTheListIsAtTheTopIsShownWithoutScrolling() {
        val solo = runBlocking { mailbox.account("solo", "Solo") }
        val folder = runBlocking { mailbox.inbox(solo) }
        runBlocking { (10..24L).forEach { mailbox.message(solo, folder, uid = it, subject = "Old subject $it") } }
        show()
        waitForText("Old subject 10")
        runBlocking { mailbox.message(solo, folder, uid = 1, subject = "Fresh subject") }
        waitForText("Fresh subject")
        compose.onNodeWithText("Fresh subject", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun singleAccountHidesAccountLabels() {
        runBlocking {
            val solo = mailbox.account("solo", "Solo")
            mailbox.message(solo, mailbox.inbox(solo), uid = 1, subject = "Only subject")
        }
        show()
        waitForText("Only subject")
        compose.onAllNodesWithTag(AccountLabelTag, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Solo", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun onAnUnfoldedScreenAMessageReplacesTheListBesideTheMenuAndBackReturnsToTheList() {
        runBlocking {
            val solo = mailbox.account("solo", "Solo")
            mailbox.message(solo, mailbox.inbox(solo), uid = 1, subject = "Wide subject", to = "solo@example.test")
        }
        val routed = mutableListOf<Long>()
        compose.setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides mailbox.container) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 700.dp))) {
                        MailboxRoute(onOpenMessage = { routed += it }, onCompose = {}, onOpenAccounts = {}, onOpenSettings = {}, onOpenOutbox = {})
                    }
                }
            }
        }
        val composeLabel = mailbox.text(R.string.mailbox_compose)
        val menuTitle = mailbox.text(R.string.app_name)
        fun reading() = compose.onAllNodesWithTag(MessageSenderTag).fetchSemanticsNodes().isNotEmpty()
        fun listShown() = compose.onAllNodesWithContentDescription(composeLabel).fetchSemanticsNodes().isNotEmpty()
        waitForText("Wide subject")
        compose.onNodeWithText(menuTitle).assertIsDisplayed()
        compose.onAllNodesWithText(mailbox.text(R.string.mailbox_pick_message)).assertCountEquals(0)
        assertTrue(listShown())

        compose.onNodeWithText("Wide subject").performClick()
        compose.waitUntil(5_000) { reading() && !listShown() }
        compose.onNodeWithText(menuTitle).assertIsDisplayed()
        assertTrue(routed.isEmpty())

        compose.onNodeWithContentDescription(mailbox.text(R.string.message_back)).performClick()
        compose.waitUntil(5_000) { !reading() && listShown() }
        compose.onNodeWithText("Wide subject").performClick()
        compose.waitUntil(5_000) { reading() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !reading() }
        compose.onNodeWithText("Wide subject").assertIsDisplayed()
    }

    @Test
    fun unknownMatchShowsNoReceivedAddressAndMatchedShowsIdentityLabel() {
        runBlocking {
            val solo = mailbox.account("solo", "Solo")
            mailbox.identity(solo, "shop@example.test", "Shopping")
            val folder = mailbox.inbox(solo)
            mailbox.message(solo, folder, uid = 1, subject = "Unknown recipient", to = "someone-else@example.test")
            mailbox.message(solo, folder, uid = 2, subject = "Known recipient", to = "shop@example.test")
        }
        show()
        waitForText("Known recipient")
        waitForText("Shopping")
        compose.onAllNodesWithText(mailbox.text(R.string.mailbox_row_received_at), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun unreadRowCarriesUnreadStateAndReadRowDoesNot() {
        runBlocking {
            val solo = mailbox.account("solo", "Solo")
            val folder = mailbox.inbox(solo)
            mailbox.message(solo, folder, uid = 1, subject = "Unread one", seen = false)
            mailbox.message(solo, folder, uid = 2, subject = "Read one", seen = true)
        }
        show()
        waitForText("Read one")
        val unread = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, mailbox.text(R.string.mailbox_row_unread))
        compose.onNode(unread and hasText("Unread one")).assertExists()
        compose.onNode(unread and hasText("Read one")).assertDoesNotExist()
    }

    @Test
    fun cachedMailStaysVisibleWhileFailedAccountsAreListedInTheBanner() {
        runBlocking {
            val personal = mailbox.account("personal", "Personal", sortOrder = 0, withCredential = true)
            val work = mailbox.account("work", "Work", sortOrder = 1)
            mailbox.message(personal, mailbox.inbox(personal), uid = 1, subject = "Cached subject")
            mailbox.message(work, mailbox.inbox(work), uid = 2, subject = "Other cached subject")
        }
        show()
        waitForText(mailbox.text(R.string.mailbox_problem_title_all))
        waitForText("Cached subject")
        waitForText(mailbox.text(R.string.mailbox_problem_reauth))
        compose.onNode(hasText("Other cached subject")).assertExists()
    }

    @Test
    fun pullingPastTheRefreshThresholdTapsOnceAndTheRefreshEndStaysQuiet() {
        runBlocking {
            val solo = mailbox.account("solo", "Solo")
            mailbox.message(solo, mailbox.inbox(solo), uid = 1, subject = "Pull me")
        }
        val taps = java.util.concurrent.CopyOnWriteArrayList<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                taps += hapticFeedbackType
            }
        }
        compose.setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides mailbox.container, LocalHapticFeedback provides feedback) {
                    MailboxRoute(onOpenMessage = {}, onCompose = {}, onOpenAccounts = {}, onOpenSettings = {}, onOpenOutbox = {})
                }
            }
        }
        waitForText("Pull me")
        compose.waitForIdle()
        taps.clear()
        compose.onNodeWithText("Pull me").performTouchInput { swipeDown(startY = centerY, endY = centerY + 1_200f, durationMillis = 800) }
        compose.waitUntil(5_000) { HapticFeedbackType.GestureThresholdActivate in taps }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), taps.toList())
    }
}
