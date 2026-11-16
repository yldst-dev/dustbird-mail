package app.dustbird.mail.ui.message

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.R
import app.dustbird.mail.core.mime.AttachmentInfo
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.nav.ComposeMode
import app.dustbird.mail.ui.theme.DustbirdTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MessageScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = MessageFixture()
    private val opened = mutableListOf<ComposeArgs>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun close() = fixture.close()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(messageId: Long) {
        compose.setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides fixture.container) {
                    MessagePane(messageId, onCompose = { opened += it })
                }
            }
        }
    }

    private fun awaitText(value: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun seen(id: Long) = runBlocking { fixture.db.messages().get(id)!!.seen }

    @Test
    fun textMessageShowsHeaderBodyAndAttachmentNoteAndIsMarkedReadThenUnread() = runBlocking<Unit> {
        val account = fixture.account("work", identityLabel = "Work", identityAddress = "work@example.com")
        val id = fixture.message(
            account,
            to = listOf(EmailAddress("work@example.com")),
            text = "first line\nsecond line",
            attachments = listOf(AttachmentInfo("report.pdf", "application/pdf", 1536), AttachmentInfo(null, "image/png", -1)),
        )
        show(id)
        awaitText("first line\nsecond line")
        compose.onNodeWithText("Quarterly report").assertExists()
        compose.onNodeWithText("Sender Name").assertExists()
        compose.onNodeWithText("sender@example.com").assertExists()
        compose.onNodeWithText(text(R.string.message_identity_value, "Work", "work@example.com"), substring = true).assertExists()
        compose.onAllNodesWithText(text(R.string.message_account)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.message_attachments, 2)).assertExists()
        compose.onNodeWithText(text(R.string.message_attachments_note)).assertExists()
        compose.onAllNodesWithText(text(R.string.message_link_open)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.message_attachments_show)).performClick()
        compose.onNodeWithText("report.pdf").assertExists()
        compose.onNodeWithText(text(R.string.message_attachment_meta, "application/pdf", "1.5 KB")).assertExists()
        compose.onNodeWithText(text(R.string.message_attachment_unnamed)).assertExists()
        compose.waitUntil(10_000) { seen(id) }
        compose.onNodeWithContentDescription(text(R.string.message_more)).performClick()
        compose.onNodeWithText(text(R.string.message_mark_unread)).performClick()
        compose.waitUntil(10_000) { !seen(id) }
    }

    @Test
    fun accountLabelAppearsOnlyWithMoreThanOneAccountAndUnknownAddressIsStatedPlainly() = runBlocking<Unit> {
        val first = fixture.account("work")
        fixture.account("home")
        val id = fixture.message(first, to = listOf(EmailAddress("someone.else@example.com")), text = "body")
        show(id)
        awaitText("body")
        compose.onNodeWithText(text(R.string.message_account)).assertExists()
        compose.onNodeWithText(text(R.string.message_identity_unknown)).assertExists()
    }

    @Test
    fun replyActionsPassTheModeAndTheSourceMessage() = runBlocking<Unit> {
        val account = fixture.account("work", identityAddress = "work@example.com")
        val id = fixture.message(account, to = listOf(EmailAddress("work@example.com"), EmailAddress("colleague@example.com")), text = "body")
        show(id)
        awaitText(text(R.string.message_reply_all))
        compose.onNodeWithText(text(R.string.message_reply)).performClick()
        compose.onNodeWithText(text(R.string.message_reply_all)).performClick()
        compose.onNodeWithText(text(R.string.message_forward)).performClick()
        assertEquals(
            listOf(ComposeMode.REPLY, ComposeMode.REPLY_ALL, ComposeMode.FORWARD),
            opened.map { it.mode },
        )
        assertTrue(opened.all { it.sourceMessageId == id })
    }

    @Test
    fun replyAllIsHiddenWhenItWouldEqualReply() = runBlocking<Unit> {
        val account = fixture.account("work", identityAddress = "work@example.com")
        val id = fixture.message(account, to = listOf(EmailAddress("work@example.com")), text = "body")
        show(id)
        awaitText("body")
        compose.waitUntil(10_000) { seen(id) }
        compose.onAllNodesWithText(text(R.string.message_reply_all)).assertCountEquals(0)
    }

    @Test
    fun remoteImagesStayBlockedUntilTheUserAllowsThemForThisMessage() = runBlocking<Unit> {
        val account = fixture.account("work", identityAddress = "work@example.com")
        val id = fixture.message(
            account,
            to = listOf(EmailAddress("work@example.com")),
            html = "<p>hello</p><img src=\"https://tracker.example/a.gif\"><img src=\"https://tracker.example/b.gif\">",
        )
        show(id)
        awaitText(text(R.string.message_images_blocked, 2))
        compose.onNodeWithText(text(R.string.message_images_allow)).performClick()
        compose.waitUntil(10_000) { runBlocking { fixture.db.messages().get(id)!!.allowRemoteImages } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text(R.string.message_images_blocked, 2)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun failedBodyExplainsTheCauseAndOffersRetry() = runBlocking<Unit> {
        val account = fixture.account("work", identityAddress = "work@example.com")
        val id = fixture.message(account, to = listOf(EmailAddress("work@example.com")), withBody = false)
        show(id)
        awaitText(text(R.string.message_failure_auth))
        compose.onNodeWithText(text(R.string.message_retry)).assertExists()
        assertTrue(!seen(id))
    }

    @Test
    fun hostileHtmlRendersInertAndALinkTapAsksBeforeOpeningAnything() = runBlocking<Unit> {
        val account = fixture.account("work", identityAddress = "work@example.com")
        val id = fixture.message(account, to = listOf(EmailAddress("work@example.com")), html = HOSTILE_HTML)
        show(id)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("message_body_html")).fetchSemanticsNodes().isNotEmpty() }
        val web = compose.awaitWebView()
        compose.waitUntil(10_000) { compose.runOnUiThread { web.progress == 100 && web.contentHeight > 0 } }
        assertTrue(compose.runOnUiThread { web.title != "pwned" })
        assertTrue(!compose.runOnUiThread { web.settings.javaScriptEnabled })
        val urlBefore = compose.runOnUiThread { web.url }
        val target = "https://target.example/path"
        val consumed = compose.runOnUiThread { web.webViewClient.shouldOverrideUrlLoading(web, FakeRequest(target)) }
        assertTrue(consumed)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.message_link_title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(target).assertExists()
        compose.onNodeWithText(text(R.string.message_link_open)).assertExists()
        assertEquals(urlBefore, compose.runOnUiThread { web.url })
        compose.onNodeWithText(text(R.string.message_link_cancel)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.message_link_title)).fetchSemanticsNodes().isEmpty() }

        compose.runOnUiThread { web.webViewClient.shouldOverrideUrlLoading(web, FakeRequest("javascript:alert(1)")) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.message_link_refused)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(text(R.string.message_link_open)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.message_link_close)).performClick()
        assertEquals(urlBefore, compose.runOnUiThread { web.url })
    }
}
