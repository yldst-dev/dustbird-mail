package app.dustbird.mail.core.net

import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import java.util.Properties
import javax.mail.URLName

object SessionConfig {
    val smtpUrl: URLName = URLName("smtp", null, -1, null, null, null)

    const val CONNECTION_TIMEOUT_MS = 15_000
    const val IO_TIMEOUT_MS = 30_000
    private const val TLS_PROTOCOLS = "TLSv1.2 TLSv1.3"

    fun imap(endpoint: ServerEndpoint, bearer: Boolean = false): Properties = base("imap", endpoint).apply {
        put("mail.imap.peek", "true")
        put("mail.imap.partialfetch", "true")
        put("mail.imap.fetchsize", "262144")
        put("mail.imap.auth.mechanisms", mechanisms(bearer))
    }

    fun smtp(endpoint: ServerEndpoint, envelopeFrom: String? = null, bearer: Boolean = false): Properties = base("smtp", endpoint).apply {
        put("mail.smtp.auth", "true")
        put("mail.smtp.auth.mechanisms", mechanisms(bearer))
        put("mail.smtp.localhost", "localhost")
        put("mail.smtp.sendpartial", "false")
        envelopeFrom?.let { put("mail.smtp.from", it) }
    }

    private fun mechanisms(bearer: Boolean) = if (bearer) "XOAUTH2" else "PLAIN LOGIN"

    private fun base(protocol: String, endpoint: ServerEndpoint): Properties {
        val prefix = "mail.$protocol"
        return Properties().apply {
            when (endpoint.security) {
                TransportSecurity.TLS -> put("$prefix.ssl.enable", "true")
                TransportSecurity.STARTTLS -> {
                    put("$prefix.starttls.enable", "true")
                    put("$prefix.starttls.required", "true")
                }
            }
            put("$prefix.ssl.checkserveridentity", "true")
            put("$prefix.ssl.protocols", TLS_PROTOCOLS)
            put("$prefix.connectiontimeout", CONNECTION_TIMEOUT_MS.toString())
            put("$prefix.timeout", IO_TIMEOUT_MS.toString())
            put("$prefix.writetimeout", IO_TIMEOUT_MS.toString())
            put("mail.mime.allowutf8", "false")
            put("mail.mime.decodefilename", "true")
        }
    }
}
