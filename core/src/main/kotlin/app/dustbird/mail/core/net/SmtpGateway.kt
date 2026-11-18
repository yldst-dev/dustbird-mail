package app.dustbird.mail.core.net

import app.dustbird.mail.core.mime.ComposedMessage
import app.dustbird.mail.core.mime.MessageParser
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.ServerEndpoint
import com.sun.mail.smtp.SMTPSendFailedException
import com.sun.mail.smtp.SMTPSenderFailedException
import com.sun.mail.smtp.SMTPTransport
import java.io.OutputStream
import javax.mail.AuthenticationFailedException
import javax.mail.SendFailedException
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.net.ssl.SSLException

enum class RejectKind { AUTH, TLS, SENDER, RECIPIENT, SERVER }

sealed interface SmtpOutcome {
    data object Accepted : SmtpOutcome
    data class Rejected(val kind: RejectKind, val code: Int?, val detail: String) : SmtpOutcome
    data class NotSent(val detail: String) : SmtpOutcome
    data class Unknown(val detail: String) : SmtpOutcome
}

fun interface SmtpSender {
    fun send(endpoint: ServerEndpoint, credentials: Credentials, message: ComposedMessage): SmtpOutcome
}

enum class ConnectFailure { AUTH, TLS, UNREACHABLE, SERVICE_DISABLED, OTHER }

object ConnectFailures {
    private val disabledHints = listOf("imap access", "imap is disabled", "imap disabled", "not enabled", "turned off", "smtp is disabled", "pop3/imap")

    fun classify(error: Throwable): ConnectFailure {
        val chain = generateSequence(error) { it.cause }.toList()
        val text = chain.joinToString(" | ") { it.message.orEmpty() }.lowercase()
        return when {
            chain.any { it is SSLException } || "can't verify identity" in text || "starttls is required" in text -> ConnectFailure.TLS
            disabledHints.any { it in text } -> ConnectFailure.SERVICE_DISABLED
            error is AuthenticationFailedException || chain.any { it is AuthenticationFailedException } -> ConnectFailure.AUTH
            chain.any { it is java.net.ConnectException || it is java.net.UnknownHostException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException } -> ConnectFailure.UNREACHABLE
            else -> ConnectFailure.OTHER
        }
    }
}

object JakartaSmtpSender : SmtpSender {
    override fun send(endpoint: ServerEndpoint, credentials: Credentials, message: ComposedMessage): SmtpOutcome {
        val session = Session.getInstance(SessionConfig.smtp(endpoint, message.envelopeFrom, credentials.bearer))
        val transport = PhaseTrackingTransport(session)
        try {
            try {
                transport.connect(endpoint.host, endpoint.port, credentials.username, credentials.password)
            } catch (error: Exception) {
                return when (ConnectFailures.classify(error)) {
                    ConnectFailure.AUTH, ConnectFailure.SERVICE_DISABLED -> SmtpOutcome.Rejected(RejectKind.AUTH, null, error.message.orEmpty())
                    ConnectFailure.TLS -> SmtpOutcome.Rejected(RejectKind.TLS, null, error.message.orEmpty())
                    ConnectFailure.UNREACHABLE, ConnectFailure.OTHER -> SmtpOutcome.NotSent(error.message.orEmpty())
                }
            }
            return try {
                val mime = MessageParser.parseRaw(message.raw)
                transport.sendMessage(mime, message.recipients.map { InternetAddress(it) }.toTypedArray())
                SmtpOutcome.Accepted
            } catch (error: Exception) {
                classifySend(error, transport.phase)
            }
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun classifySend(error: Exception, phase: Phase): SmtpOutcome {
        val detail = error.message.orEmpty()
        val sender = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SMTPSenderFailedException>().firstOrNull()
        return when {
            sender != null -> SmtpOutcome.Rejected(RejectKind.SENDER, sender.returnCode, detail)
            error is SMTPSendFailedException && phase >= Phase.DATA -> SmtpOutcome.Rejected(RejectKind.SERVER, error.returnCode, detail)
            error is SMTPSendFailedException -> SmtpOutcome.Rejected(RejectKind.RECIPIENT, error.returnCode, detail)
            error is SendFailedException -> SmtpOutcome.Rejected(RejectKind.RECIPIENT, null, detail)
            phase >= Phase.DATA -> SmtpOutcome.Unknown(detail)
            else -> SmtpOutcome.NotSent(detail)
        }
    }

    enum class Phase { ENVELOPE, DATA, FINISHING }

    private class PhaseTrackingTransport(session: Session) : SMTPTransport(session, SessionConfig.smtpUrl) {
        @Volatile
        var phase: Phase = Phase.ENVELOPE
            private set

        override fun data(): OutputStream {
            val stream = super.data()
            phase = Phase.DATA
            return stream
        }

        override fun finishData() {
            phase = Phase.FINISHING
            super.finishData()
        }
    }
}
