package app.dustbird.mail.core.net

import app.dustbird.mail.core.mime.ComposedMessage
import app.dustbird.mail.core.mime.MimeComposer
import app.dustbird.mail.core.mime.OutgoingMessage
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.testing.Fixtures
import app.dustbird.mail.core.testing.ScriptedSmtpServer
import app.dustbird.mail.core.testing.ScriptedSmtpServer.Scenario
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtpSenderTest {
    private val credentials = Credentials("user@example.test", "secret")

    private fun message(): ComposedMessage = MimeComposer.compose(
        OutgoingMessage(
            from = EmailAddress("sender@example.test", "보내는 사람"),
            to = listOf(EmailAddress("to@example.test")),
            cc = emptyList(),
            bcc = listOf(EmailAddress("hidden@example.test")),
            subject = "시험 제목",
            text = "본문입니다",
            messageId = "<fixed-1@example.test>",
            inReplyTo = null,
            references = emptyList(),
            sentAt = Instant.parse("2026-11-16T00:00:00Z"),
        ),
    )

    private fun run(scenario: Scenario, keystore: File = Fixtures.file("test-server.p12"), host: String = "localhost", block: (ScriptedSmtpServer, SmtpOutcome) -> Unit) {
        ScriptedSmtpServer(scenario, keystore).use { server ->
            val outcome = JakartaSmtpSender.send(ServerEndpoint(host, server.port, TransportSecurity.STARTTLS), credentials, message())
            block(server, outcome)
        }
    }

    @Test
    fun starttlsSubmissionIsAcceptedAndUsesTls() = run(Scenario.ACCEPT) { server, outcome ->
        assertIs<SmtpOutcome.Accepted>(outcome)
        assertTrue(server.tlsUsed)
        assertEquals("sender@example.test", server.envelopeFrom)
        assertEquals(listOf("to@example.test", "hidden@example.test"), server.recipients)
        assertFalse(server.data!!.contains("Bcc:", ignoreCase = true))
        assertTrue(server.data!!.contains("Message-ID: <fixed-1@example.test>"))
    }

    @Test
    fun serverWithoutStarttlsIsRefusedInsteadOfDowngrading() = run(Scenario.NO_STARTTLS) { server, outcome ->
        val rejected = assertIs<SmtpOutcome.Rejected>(outcome)
        assertEquals(RejectKind.TLS, rejected.kind)
        assertTrue(server.data == null)
    }

    @Test
    fun certificateForAnotherHostIsRefused() = run(Scenario.ACCEPT, Fixtures.file("wrong-host.p12")) { server, outcome ->
        val rejected = assertIs<SmtpOutcome.Rejected>(outcome)
        assertEquals(RejectKind.TLS, rejected.kind)
        assertTrue(server.data == null)
    }

    @Test
    fun connectingByIpAddressFailsHostNameCheck() = run(Scenario.ACCEPT, host = "127.0.0.1") { _, outcome ->
        assertEquals(RejectKind.TLS, assertIs<SmtpOutcome.Rejected>(outcome).kind)
    }

    @Test
    fun badCredentialsAreRejectedAsAuth() = run(Scenario.REJECT_AUTH) { _, outcome ->
        assertEquals(RejectKind.AUTH, assertIs<SmtpOutcome.Rejected>(outcome).kind)
    }

    @Test
    fun senderAddressRefusalIsReportedAsSender() = run(Scenario.REJECT_MAIL) { _, outcome ->
        val rejected = assertIs<SmtpOutcome.Rejected>(outcome)
        assertEquals(RejectKind.SENDER, rejected.kind)
        assertEquals(553, rejected.code)
    }

    @Test
    fun recipientRefusalIsReportedAsRecipient() = run(Scenario.REJECT_RCPT) { _, outcome ->
        assertEquals(RejectKind.RECIPIENT, assertIs<SmtpOutcome.Rejected>(outcome).kind)
    }

    @Test
    fun finalReplyRefusalIsReportedAsServerRejection() = run(Scenario.REJECT_FINAL) { _, outcome ->
        val rejected = assertIs<SmtpOutcome.Rejected>(outcome)
        assertEquals(RejectKind.SERVER, rejected.kind)
        assertEquals(554, rejected.code)
    }

    @Test
    fun dropDuringDataIsUnknown() = run(Scenario.DROP_DURING_DATA) { _, outcome ->
        assertIs<SmtpOutcome.Unknown>(outcome)
    }

    @Test
    fun dropAfterDataBeforeReplyIsUnknown() = run(Scenario.DROP_AFTER_DATA) { server, outcome ->
        assertIs<SmtpOutcome.Unknown>(outcome)
        assertNotNull(server.data)
    }

    @Test
    fun refusedConnectionIsNotSent() {
        val port = Fixtures.freePort()
        val outcome = JakartaSmtpSender.send(ServerEndpoint("localhost", port, TransportSecurity.STARTTLS), credentials, message())
        assertIs<SmtpOutcome.NotSent>(outcome)
    }
}
