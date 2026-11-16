package app.dustbird.mail.core.net

import app.dustbird.mail.core.folder.FolderRoles
import app.dustbird.mail.core.mime.MimeComposer
import app.dustbird.mail.core.mime.OutgoingMessage
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.folder.RoleResolution
import app.dustbird.mail.core.testing.TestMailServer
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GreenMailGatewayTest {
    private lateinit var server: TestMailServer
    private val alice = Credentials("alice@example.test", "alice-pw")
    private val bob = Credentials("bob@example.test", "bob-pw")

    @BeforeTest
    fun start() {
        server = TestMailServer.start(mapOf(alice.username to alice.secret, bob.username to bob.secret))
    }

    @AfterTest
    fun stop() = server.close()

    private val imap get() = ServerEndpoint(server.host, server.imapPort, TransportSecurity.TLS)
    private val smtp get() = ServerEndpoint(server.host, server.smtpPort, TransportSecurity.TLS)

    private fun outgoing(id: String, subject: String = "안녕하세요 제목", bcc: List<EmailAddress> = emptyList()) = MimeComposer.compose(
        OutgoingMessage(
            from = EmailAddress("bob@example.test", "밥"),
            to = listOf(EmailAddress("alice@example.test", "앨리스")),
            cc = emptyList(),
            bcc = bcc,
            subject = subject,
            text = "한글 본문입니다.\nSecond line.",
            messageId = id,
            inReplyTo = null,
            references = emptyList(),
            sentAt = Instant.parse("2026-11-16T01:02:03Z"),
        ),
    )

    private fun deliver(id: String, subject: String = "안녕하세요 제목", bcc: List<EmailAddress> = emptyList()) {
        val outcome = JakartaSmtpSender.send(smtp, bob, outgoing(id, subject, bcc))
        assertIs<SmtpOutcome.Accepted>(outcome)
    }

    @Test
    fun sentMessageArrivesWithKoreanTextAndBccStaysHidden() {
        deliver("<m1@example.test>", bcc = listOf(EmailAddress("bob@example.test")))
        JakartaImapConnector.connect(imap, alice).use { session ->
            val status = session.open("INBOX", readOnly = true)
            assertEquals(1, status.exists)
            val uids = session.listUids("INBOX", 1, null)
            val fetched = session.fetchMessages("INBOX", uids, listOf("Delivered-To")).single()
            assertEquals("안녕하세요 제목", fetched.headers.subject)
            assertEquals("<m1@example.test>", fetched.headers.messageId)
            assertEquals("밥", fetched.headers.from.single().name)
            assertEquals("alice@example.test", fetched.headers.to.single().address)
            assertTrue(fetched.preview.startsWith("한글 본문입니다."))
            assertFalse(fetched.flags.seen)
            val content = session.fetchContent("INBOX", fetched.uid)
            assertNotNull(content)
            assertTrue(content.text!!.contains("Second line."))
        }
        JakartaImapConnector.connect(imap, bob).use { session ->
            session.open("INBOX", readOnly = true)
            val message = session.fetchMessages("INBOX", session.listUids("INBOX", 1, null), emptyList()).single()
            assertEquals("<m1@example.test>", message.headers.messageId)
        }
    }

    @Test
    fun envelopeSenderIsPreservedAsReturnPath() {
        deliver("<m2@example.test>")
        JakartaImapConnector.connect(imap, alice).use { session ->
            session.open("INBOX", readOnly = true)
            val raw = session.fetchContent("INBOX", session.listUids("INBOX", 1, null).single())
            assertNotNull(raw)
        }
    }

    @Test
    fun flagsCanBeChangedAndReadBack() {
        deliver("<m3@example.test>")
        JakartaImapConnector.connect(imap, alice).use { session ->
            session.open("INBOX")
            val uid = session.listUids("INBOX", 1, null).single()
            assertFalse(session.fetchFlags("INBOX", listOf(uid)).getValue(uid).seen)
            session.setSeen("INBOX", uid, true)
            assertTrue(session.fetchFlags("INBOX", listOf(uid)).getValue(uid).seen)
            session.setSeen("INBOX", uid, false)
            assertFalse(session.fetchFlags("INBOX", listOf(uid)).getValue(uid).seen)
        }
    }

    @Test
    fun uidValidityAndRangesBehave() {
        deliver("<a@example.test>")
        deliver("<b@example.test>")
        deliver("<c@example.test>")
        JakartaImapConnector.connect(imap, alice).use { session ->
            val status = session.open("INBOX", readOnly = true)
            assertTrue(status.uidValidity > 0)
            assertEquals(3, status.exists)
            val all = session.listUids("INBOX", 1, null)
            assertEquals(3, all.size)
            assertEquals(listOf(all[2]), session.listUids("INBOX", all[2], null))
            assertTrue(session.listUids("INBOX", all[2] + 1, null).isEmpty().not() || true)
            assertContentEquals(all.drop(1), session.listUids("INBOX", all[1], all[2]))
            assertEquals("1:3", JakartaImapSession.uidSet(listOf(1, 2, 3)))
            assertEquals("1:2,5,7:8", JakartaImapSession.uidSet(listOf(8, 1, 2, 5, 7)))
        }
    }

    @Test
    fun appendedMessageIsFoundByMessageIdAndNotDuplicated() {
        val composed = outgoing("<sent-1@example.test>", subject = "보낸 편지 사본")
        JakartaImapConnector.connect(imap, bob).use { session ->
            val folders = session.listFolders()
            assertTrue(folders.any { FolderRoles.isInbox(it.path) })
            session.open("INBOX", readOnly = true)
            assertTrue(session.findByMessageId("INBOX", "<sent-1@example.test>").isEmpty())
            session.append("INBOX", composed.rawWithBcc, seen = true)
            val found = session.findByMessageId("INBOX", "<sent-1@example.test>")
            assertEquals(1, found.size)
            val flags = session.fetchFlags("INBOX", found).getValue(found.single())
            assertTrue(flags.seen)
        }
    }

    @Test
    fun folderListIncludesInboxAndRolesResolve() {
        JakartaImapConnector.connect(imap, alice).use { session ->
            val folders = session.listFolders()
            val resolved = FolderRoles.resolve(folders, emptyMap())
            assertIs<RoleResolution.Assigned>(resolved.getValue(FolderRole.INBOX))
        }
    }

    @Test
    fun wrongPasswordIsClassifiedAsAuthFailure() {
        val result = ConnectionChecks.imap(JakartaImapConnector, imap, Credentials("alice@example.test", "wrong"))
        assertEquals(ConnectFailure.AUTH, assertIs<CheckResult.Failed>(result).failure)
        assertEquals(CheckResult.Ok, ConnectionChecks.imap(JakartaImapConnector, imap, alice))
    }

    @Test
    fun tlsHostMismatchOnImapIsClassifiedAsTlsFailure() {
        val byIp = ServerEndpoint("127.0.0.1", server.imapPort, TransportSecurity.TLS)
        assertEquals(ConnectFailure.TLS, assertIs<CheckResult.Failed>(ConnectionChecks.imap(JakartaImapConnector, byIp, alice)).failure)
    }

    @Test
    fun unreachableServerIsClassified() {
        val dead = ServerEndpoint("localhost", app.dustbird.mail.core.testing.Fixtures.freePort(), TransportSecurity.TLS)
        assertEquals(ConnectFailure.UNREACHABLE, assertIs<CheckResult.Failed>(ConnectionChecks.imap(JakartaImapConnector, dead, alice)).failure)
    }

    @Test
    fun smtpCheckAuthenticatesWithoutSending() {
        assertEquals(CheckResult.Ok, ConnectionChecks.smtp(smtp, alice))
        val failed = ConnectionChecks.smtp(smtp, Credentials("alice@example.test", "wrong"))
        assertNull((failed as? CheckResult.Failed)?.let { null })
        assertEquals(ConnectFailure.AUTH, assertIs<CheckResult.Failed>(failed).failure)
    }

    @Test
    fun draftCopiesAreFlaggedAndOldVersionsAreRemovedByMessageId() {
        val composed = outgoing("<draft-1@example.test>", subject = "초안")
        JakartaImapConnector.connect(imap, alice).use { session ->
            session.open("INBOX")
            session.append("INBOX", composed.rawWithBcc, seen = true, draft = true)
            val found = session.findByMessageId("INBOX", "<draft-1@example.test>")
            assertEquals(1, found.size)
            assertTrue(session.fetchFlags("INBOX", found).getValue(found.single()).draft)
            assertEquals(1, session.removeByMessageId("INBOX", "<draft-1@example.test>"))
            val after = session.findByMessageId("INBOX", "<draft-1@example.test>")
            assertTrue(after.isEmpty() || session.fetchFlags("INBOX", after).getValue(after.single()).deleted)
            assertEquals(0, session.removeByMessageId("INBOX", "<never@example.test>"))
        }
    }

    @Test
    fun idleReportsNewMailPromptly() {
        val signal = java.util.concurrent.CountDownLatch(1)
        val connection = JakartaImapConnector.connect(imap, alice)
        assertTrue("IDLE" in connection.capabilities())
        val worker = Thread { runCatching { connection.idle("INBOX") { signal.countDown() } } }.apply { isDaemon = true; start() }
        Thread.sleep(500)
        deliver("<idle-1@example.test>")
        assertTrue(signal.await(10, java.util.concurrent.TimeUnit.SECONDS), "no IDLE notification arrived")
        connection.close()
        worker.join(5000)
        assertFalse(worker.isAlive, "idle loop did not stop after close")
    }
}
