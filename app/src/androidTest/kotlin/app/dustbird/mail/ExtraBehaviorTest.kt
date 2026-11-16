package app.dustbird.mail

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.dustbird.mail.core.identity.Rejection
import app.dustbird.mail.core.identity.RecipientMatch
import app.dustbird.mail.core.identity.RuleResult
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.db.IdentityEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.MessageEntity
import app.dustbird.mail.data.db.OutboxEntity
import app.dustbird.mail.data.repo.AddAccountResult
import app.dustbird.mail.data.repo.DraftContent
import app.dustbird.mail.data.repo.IdentityInput
import app.dustbird.mail.data.repo.IdentityResult
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.data.repo.NewAccount
import app.dustbird.mail.data.secure.CredentialStore
import app.dustbird.mail.work.SyncWorker
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class InMemoryCredentialStore : CredentialStore {
    private val secrets = HashMap<Long, String>()
    override fun put(accountId: Long, secret: String) { secrets[accountId] = secret }
    override fun get(accountId: Long): String? = secrets[accountId]
    override fun delete(accountId: Long) { secrets.remove(accountId) }
    override fun deleteAll() = secrets.clear()
}

@RunWith(AndroidJUnit4::class)
class ExtraBehaviorTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val imap = ServerEndpoint("10.0.2.2", 3993, TransportSecurity.TLS)
    private val smtp = ServerEndpoint("10.0.2.2", 3465, TransportSecurity.TLS)
    private val opened = mutableListOf<MailDatabase>()
    private val realContainer get() = (context.applicationContext as MailApplication).container

    @After
    fun tearDown() = runBlocking<Unit> {
        opened.forEach { it.close() }
        if (hostServerUp()) realContainer.maintenance.resetEverything()
    }

    private fun hostServerUp() = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 3993), 2000) } }.isSuccess

    private fun isolatedContainer(): AppContainer {
        val db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build().also { opened += it }
        return AppContainer(context, db = db, credentials = InMemoryCredentialStore())
    }

    private fun accountEntity(label: String) = AccountEntity(
        providerId = "custom", displayName = label, label = label, username = "$label@example.test", imapHost = "imap.invalid", imapPort = 993,
        imapSecurity = TransportSecurity.TLS, smtpHost = "smtp.invalid", smtpPort = 587, smtpSecurity = TransportSecurity.STARTTLS,
        authMethod = AuthMethod.APP_PASSWORD, sortOrder = 0, syncMode = SyncMode.MANUAL, notificationsEnabled = true, needsReauth = false,
        mailboxKey = "imap.invalid|$label@example.test",
    )

    private fun messageEntity(accountId: Long, folderId: Long, subject: String, to: String) = MessageEntity(
        accountId = accountId, folderId = folderId, uid = 1, uidValidity = 1, messageId = "<x@example.test>", inReplyTo = null, references = "",
        fromName = "보낸 사람", fromAddress = "sender@example.test", replyTo = "", toAddresses = "\u001F$to", ccAddresses = "", deliveryValues = "",
        subject = subject, sortDate = 1, preview = "", size = 1, hasAttachment = false, seen = false, answered = false, flagged = false,
        allowRemoteImages = false,
    )

    private fun folderEntity(accountId: Long) = FolderEntity(
        accountId = accountId, path = "INBOX", delimiter = "/", attributes = "", role = FolderRole.INBOX, roleConfirmed = false,
        uidValidity = 1, highestUid = 1, baselineComplete = true, syncedAt = 0,
    )

    @Test
    fun removingAnIdentityMovesItsDraftsKeepsTheDefaultAndRefusesTheLastOne() = runBlocking<Unit> {
        val container = isolatedContainer()
        val accountId = assertIs<AddAccountResult.Added>(
            container.accounts.addAccount(NewAccount("custom", "a", "a", "a@example.test", "a@example.test", "", imap, smtp), "pw"),
        ).accountId
        val first = container.accounts.identities.first().single { it.accountId == accountId }
        val second = assertIs<IdentityResult.Saved>(container.accounts.addIdentity(accountId, IdentityInput("biz@example.test", "", "", "biz"))).identityId
        val draftId = container.drafts.save(DraftContent(null, second, listOf(EmailAddress("x@example.test")), emptyList(), emptyList(), "초안", "본문", null, emptyList()))

        val removal = assertIs<RuleResult.Ok<*>>(container.accounts.removeIdentity(second)).value as app.dustbird.mail.data.repo.IdentityRemoval
        assertEquals(first.id, removal.fallbackIdentityId)
        assertEquals(1, removal.movedDrafts)
        assertEquals(first.id, container.drafts.load(draftId)!!.identityId)
        val remaining = container.accounts.identities.first().filter { it.accountId == accountId }
        assertEquals(listOf(first.id), remaining.map { it.id })
        assertTrue(remaining.single().isAccountDefault)
        assertEquals(Rejection.LAST_IDENTITY, assertIs<RuleResult.Rejected>(container.accounts.removeIdentity(first.id)).reason)
    }

    @Test
    fun anIdentityAddedLaterLabelsMailThatArrivedBefore() = runBlocking<Unit> {
        val container = isolatedContainer()
        val accountId = assertIs<AddAccountResult.Added>(
            container.accounts.addAccount(NewAccount("custom", "a", "a", "a@example.test", "a@example.test", "", imap, smtp), "pw"),
        ).accountId
        val folderId = container.db.folders().insert(folderEntity(accountId))
        container.db.messages().upsert(messageEntity(accountId, folderId, "과거 메일", "late@example.test"))

        assertEquals(RecipientMatch.Unknown, container.mail.messages(MailboxScope.Unified, 10).first().single().match)
        val added = assertIs<IdentityResult.Saved>(container.accounts.addIdentity(accountId, IdentityInput("late@example.test", "", "", "late"))).identityId
        assertEquals(RecipientMatch.Matched(listOf(added)), container.mail.messages(MailboxScope.Unified, 10).first().single().match)
    }

    @Test
    fun anOutgoingMailInterruptedWhileSendingIsRecoveredAsUnknownNotResent() = runBlocking<Unit> {
        val container = isolatedContainer()
        val accountId = container.db.accounts().insert(accountEntity("a"))
        val identityId = container.db.identities().insert(
            IdentityEntity(accountId = accountId, address = "a@example.test", addressKey = "a@example.test", displayName = "", signature = "", label = "a", labelKey = "a", sortOrder = 0, isAccountDefault = true, verification = SendVerification.UNVERIFIED),
        )
        val outboxId = container.db.outbox().insert(
            OutboxEntity(accountId = accountId, identityId = identityId, state = SendState.SENDING, attempts = 1, failure = null, messageId = "<o@example.test>", subject = "s", recipientSummary = "r", raw = byteArrayOf(), rawWithBcc = byteArrayOf(), envelopeFrom = "a@example.test", recipients = "x@example.test", createdAt = 0, updatedAt = 0),
        )
        container.send.recoverAfterCrash()
        assertEquals(SendState.UNKNOWN, container.db.outbox().get(outboxId)!!.state)
    }

    @Test
    fun tappingANotificationTargetOpensThatMessage() = runBlocking<Unit> {
        val container = realContainer
        container.maintenance.resetEverything()
        val accountId = container.db.accounts().insert(accountEntity("tap"))
        val folderId = container.db.folders().insert(folderEntity(accountId))
        val rowId = container.db.messages().upsert(messageEntity(accountId, folderId, "알림에서 열린 메일", "tap@example.test"))
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_MESSAGE_ID, rowId)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithTextCount("알림에서 열린 메일") > 0 }
            compose.onNodeWithText("알림에서 열린 메일").assertExists()
        }
    }

    @Test
    fun thePeriodicWorkerSyncsAccountsInPeriodicMode() = runBlocking<Unit> {
        assumeTrue("host test server is not running", hostServerUp())
        val container = realContainer
        container.maintenance.resetEverything()
        val accountId = assertIs<AddAccountResult.Added>(
            container.accounts.addAccount(NewAccount("custom", "w", "w", "tester@example.test", "tester@example.test", "", imap, smtp), "tester-pw"),
        ).accountId
        val session = Session.getInstance(Properties().apply { put("mail.smtp.ssl.enable", "true"); put("mail.smtp.auth", "true") })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress("friend@example.test"))
            setRecipient(Message.RecipientType.TO, InternetAddress("tester@example.test"))
            setSubject("주기 작업 시험 ${System.nanoTime()}", "UTF-8")
            setText("본문", "UTF-8")
        }
        session.getTransport("smtp").apply {
            connect("10.0.2.2", 3465, "friend@example.test", "friend-pw")
            sendMessage(message, message.allRecipients)
            close()
        }
        val result = TestListenableWorkerBuilder<SyncWorker>(context.applicationContext).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        val inbox = assertNotNull(container.db.folders().byRole(accountId, FolderRole.INBOX))
        assertTrue(container.db.messages().count(inbox.id) > 0)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
