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
    fun envelopeSenderIsSeparateFromTheFromHeaderAndReachesReturnPath() {
        val composed = outgoing("<m2@example.test>")
        val withBounce = app.dustbird.mail.core.mime.ComposedMessage(composed.raw, composed.rawWithBcc, "bounce@example.test", composed.recipients)
        assertIs<SmtpOutcome.Accepted>(JakartaSmtpSender.send(smtp, bob, withBounce))
        val mailSession = javax.mail.Session.getInstance(java.util.Properties().apply {
            put("mail.imap.ssl.enable", "true")
            put("mail.imap.ssl.checkserveridentity", "true")
        })
        val store = mailSession.getStore("imap")
        store.connect(server.host, server.imapPort, alice.username, alice.secret)
        val inbox = store.getFolder("INBOX")
        inbox.open(javax.mail.Folder.READ_ONLY)
        val received = inbox.getMessage(1)
        assertEquals(listOf("<bounce@example.test>"), received.getHeader("Return-Path").toList())
        assertEquals("bob@example.test", (received.from.single() as javax.mail.internet.InternetAddress).address)
        inbox.close(false)
        store.close()
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
        val dead = ServerEndpoint("localhost", 1, TransportSecurity.TLS)
        assertEquals(ConnectFailure.UNREACHABLE, assertIs<CheckResult.Failed>(ConnectionChecks.imap(JakartaImapConnector, dead, alice)).failure)
    }

    @Test
    fun smtpCheckAuthenticatesWithoutSending() {
        assertEquals(CheckResult.Ok, ConnectionChecks.smtp(smtp, alice))
        val failed = ConnectionChecks.smtp(smtp, Credentials("alice@example.test", "wrong"))
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

    private fun deliverMime(subject: String, build: (javax.mail.internet.MimeMessage) -> Unit) {
        val session = javax.mail.Session.getInstance(java.util.Properties().apply { put("mail.smtp.ssl.enable", "true"); put("mail.smtp.auth", "true") })
        val message = javax.mail.internet.MimeMessage(session).apply {
            setFrom(javax.mail.internet.InternetAddress("bob@example.test"))
            setRecipient(javax.mail.Message.RecipientType.TO, javax.mail.internet.InternetAddress("alice@example.test"))
            setSubject(subject, "UTF-8")
            build(this)
            saveChanges()
        }
        session.getTransport("smtp").apply {
            connect(server.host, server.smtpPort, bob.username, bob.secret)
            sendMessage(message, message.allRecipients)
            close()
        }
    }

    @Test
    fun alternativeAndAttachmentMailsAreReadOverImapWithLazyParts() {
        deliverMime("대체 본문") { message ->
            val alternative = javax.mail.internet.MimeMultipart("alternative")
            alternative.addBodyPart(javax.mail.internet.MimeBodyPart().apply { setText("평문 버전", "UTF-8") })
            alternative.addBodyPart(javax.mail.internet.MimeBodyPart().apply { setContent("<p>HTML 버전</p>", "text/html; charset=UTF-8") })
            message.setContent(alternative)
        }
        deliverMime("첨부 메일") { message ->
            val mixed = javax.mail.internet.MimeMultipart("mixed")
            mixed.addBodyPart(javax.mail.internet.MimeBodyPart().apply { setText("본문 텍스트", "UTF-8") })
            mixed.addBodyPart(javax.mail.internet.MimeBodyPart().apply {
                setContent("attachment body", "text/plain")
                fileName = "계약서.txt"
                disposition = "attachment"
            })
            message.setContent(mixed)
        }
        JakartaImapConnector.connect(imap, alice).use { session ->
            session.open("INBOX", readOnly = true)
            val uids = session.listUids("INBOX", 1, null)
            val alternative = session.fetchContent("INBOX", uids[0])!!
            assertEquals("평문 버전", alternative.text)
            assertEquals("<p>HTML 버전</p>", alternative.html)
            val mixed = session.fetchContent("INBOX", uids[1])!!
            assertEquals("본문 텍스트", mixed.text)
            assertEquals(listOf("계약서.txt"), mixed.attachments.map { it.fileName })
        }
    }
}
