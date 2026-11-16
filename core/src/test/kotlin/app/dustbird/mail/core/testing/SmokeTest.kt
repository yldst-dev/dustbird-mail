package app.dustbird.mail.core.testing

import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun sendOverTlsThenReadBackOverTls() {
        Fixtures.trustFixtures()
        TestMailServer.start(mapOf("alice@example.test" to "alice-pw")).use { server ->
            val props = Properties().apply {
                put("mail.imap.ssl.enable", "true")
                put("mail.imap.ssl.checkserveridentity", "true")
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
                put("mail.smtp.auth", "true")
            }
            val session = Session.getInstance(props)
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress("alice@example.test"))
                setRecipient(Message.RecipientType.TO, InternetAddress("alice@example.test"))
                setSubject("한글 제목", "UTF-8")
                setText("본문", "UTF-8")
            }
            session.getTransport("smtp").use { transport ->
                transport.connect(server.host, server.smtpPort, "alice@example.test", "alice-pw")
                transport.sendMessage(message, message.allRecipients)
            }
            val store = session.getStore("imap")
            store.connect(server.host, server.imapPort, "alice@example.test", "alice-pw")
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            assertEquals(1, inbox.messageCount)
            assertEquals("한글 제목", inbox.getMessage(1).subject)
            assertTrue(inbox.getMessage(1).getHeader("Message-ID").isNotEmpty())
            inbox.close(false)
            store.close()
        }
    }
}

private inline fun <T : javax.mail.Service, R> T.use(block: (T) -> R): R = try { block(this) } finally { close() }
