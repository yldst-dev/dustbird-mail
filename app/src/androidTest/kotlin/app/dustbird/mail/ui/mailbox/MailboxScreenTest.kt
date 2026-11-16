package app.dustbird.mail.ui.mailbox

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.R
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.theme.DustbirdTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
            mailbox.message(work, mailbox.inbox(work), uid = 2, subject = "Work subject")
        }
        show()
        waitForText("Personal subject")
        waitForText("Work subject")
        compose.onAllNodesWithTag(AccountLabelTag, useUnmergedTree = true).assertCountEquals(2)
        compose.onNode(hasTestTag(AccountLabelTag) and hasText("Personal"), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag(AccountLabelTag) and hasText("Work"), useUnmergedTree = true).assertExists()
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
    fun unknownMatchShowsPlainUnknownTextAndMatchedShowsIdentityLabel() {
        runBlocking {
            val solo = mailbox.account("solo", "Solo")
            mailbox.identity(solo, "shop@example.test", "Shopping")
            val folder = mailbox.inbox(solo)
            mailbox.message(solo, folder, uid = 1, subject = "Unknown recipient", to = "someone-else@example.test")
            mailbox.message(solo, folder, uid = 2, subject = "Known recipient", to = "shop@example.test")
        }
        show()
        waitForText("Known recipient")
        waitForText(mailbox.text(R.string.mailbox_row_unknown_identity))
        waitForText("Shopping")
        compose.onAllNodesWithText(mailbox.text(R.string.mailbox_row_unknown_identity), useUnmergedTree = true).assertCountEquals(1)
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
}
