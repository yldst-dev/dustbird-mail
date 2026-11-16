package app.dustbird.mail.ui.message

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.AppContainer
import app.dustbird.mail.core.mime.AttachmentInfo
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.db.IdentityEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.MessageBodyEntity
import app.dustbird.mail.data.db.MessageEntity

internal class FakeRequest(private val target: String, private val mainFrame: Boolean = false) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(target)
    override fun isForMainFrame(): Boolean = mainFrame
    override fun isRedirect(): Boolean = false
    override fun hasGesture(): Boolean = true
    override fun getMethod(): String = "GET"
    override fun getRequestHeaders(): Map<String, String> = emptyMap()
}

internal fun View.findWebView(): WebView? = when (this) {
    is WebView -> this
    is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
    else -> null
}

internal fun AndroidComposeTestRule<*, androidx.activity.ComponentActivity>.awaitWebView(): WebView {
    waitUntil(10_000) { runOnUiThread { activity.window.decorView.findWebView() != null } }
    return runOnUiThread { activity.window.decorView.findWebView()!! }
}

internal const val HOSTILE_HTML = """<title>safe</title><script>document.title='pwned'</script>""" +
    """<img src="x" onerror="document.title='pwned'"><a href="javascript:document.title='pwned'">script link</a>""" +
    """<iframe src="https://hostile.example/frame"></iframe><form action="https://hostile.example/post"><input name="a"></form>""" +
    """<img src="https://tracker.example/pixel.gif"><p>readable text</p><a href="https://target.example/path">visible text</a>"""

internal class MessageFixture {
    val db: MailDatabase
    val container: AppContainer

    init {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        container = AppContainer(context, db = db)
    }

    fun close() = db.close()

    suspend fun account(key: String, identityLabel: String = key, identityAddress: String = "$key@example.com"): Long {
        val accountId = db.accounts().insert(
            AccountEntity(
                providerId = "custom", displayName = key, label = key, username = "$key@example.com", imapHost = "imap.example.com", imapPort = 993,
                imapSecurity = TransportSecurity.TLS, smtpHost = "smtp.example.com", smtpPort = 587, smtpSecurity = TransportSecurity.STARTTLS,
                authMethod = AuthMethod.APP_PASSWORD, sortOrder = db.accounts().nextSortOrder(), syncMode = SyncMode.MANUAL,
                notificationsEnabled = true, needsReauth = false, mailboxKey = key,
            ),
        )
        db.identities().insert(
            IdentityEntity(
                accountId = accountId, address = identityAddress, addressKey = identityAddress.lowercase(), displayName = "", signature = "",
                label = identityLabel, labelKey = identityLabel.lowercase(), sortOrder = 0, isAccountDefault = true, verification = SendVerification.UNVERIFIED,
            ),
        )
        db.folders().insert(
            FolderEntity(
                accountId = accountId, path = "INBOX", delimiter = "/", attributes = "", role = FolderRole.INBOX, roleConfirmed = false,
                uidValidity = 1, highestUid = 0, baselineComplete = false, syncedAt = 0,
            ),
        )
        return accountId
    }

    suspend fun message(
        accountId: Long,
        to: List<EmailAddress>,
        cc: List<EmailAddress> = emptyList(),
        text: String? = null,
        html: String? = null,
        attachments: List<AttachmentInfo> = emptyList(),
        withBody: Boolean = true,
        subject: String = "Quarterly report",
    ): Long {
        val folderId = db.folders().byRole(accountId, FolderRole.INBOX)!!.id
        val id = db.messages().upsert(
            MessageEntity(
                accountId = accountId, folderId = folderId, uid = 1, uidValidity = 1, messageId = "<m$accountId@example.com>", inReplyTo = null,
                references = "", fromName = "Sender Name", fromAddress = "sender@example.com", replyTo = "", toAddresses = Codec.addresses(to),
                ccAddresses = Codec.addresses(cc), deliveryValues = "", subject = subject, sortDate = 1_794_000_000_000, preview = "",
                size = 1, hasAttachment = attachments.isNotEmpty(), seen = false, answered = false, flagged = false, allowRemoteImages = false,
            ),
        )
        if (withBody) db.messages().upsertBody(MessageBodyEntity(id, text, html, Codec.attachments(attachments)))
        return id
    }
}
