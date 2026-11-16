package app.dustbird.mail.core.net

import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.ServerEndpoint
import javax.mail.Session
import com.sun.mail.smtp.SMTPTransport

sealed interface CheckResult {
    data object Ok : CheckResult
    data class Failed(val failure: ConnectFailure, val detail: String) : CheckResult
}

object ConnectionChecks {
    fun imap(connector: ImapConnector, endpoint: ServerEndpoint, credentials: Credentials): CheckResult = try {
        connector.connect(endpoint, credentials).use { it.listFolders() }
        CheckResult.Ok
    } catch (error: Exception) {
        CheckResult.Failed(ConnectFailures.classify(error), error.message.orEmpty())
    }

    fun smtp(endpoint: ServerEndpoint, credentials: Credentials): CheckResult = try {
        val transport = SMTPTransport(Session.getInstance(SessionConfig.smtp(endpoint)), SessionConfig.smtpUrl)
        try {
            transport.connect(endpoint.host, endpoint.port, credentials.username, credentials.secret)
        } finally {
            runCatching { transport.close() }
        }
        CheckResult.Ok
    } catch (error: Exception) {
        CheckResult.Failed(ConnectFailures.classify(error), error.message.orEmpty())
    }
}
