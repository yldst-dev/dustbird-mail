package app.dustbird.mail.core.mime

import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.model.EmailAddress
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Date
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

data class OutgoingMessage(
    val from: EmailAddress,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>,
    val subject: String,
    val text: String,
    val messageId: String,
    val inReplyTo: String?,
    val references: List<String>,
    val sentAt: Instant,
)

class ComposedMessage(
    val raw: ByteArray,
    val rawWithBcc: ByteArray,
    val envelopeFrom: String,
    val recipients: List<String>,
)

object MimeComposer {
    private val session: Session = Session.getInstance(Properties().apply { put("mail.mime.allowutf8", "false") })
    private val messageIdShape = Regex("^<[^<>\\s@]+@[^<>\\s@]+>$")

    fun compose(message: OutgoingMessage): ComposedMessage {
        val everyone = message.to + message.cc + message.bcc
        require(everyone.isNotEmpty()) { "At least one recipient is required" }
        (everyone + message.from).forEach {
            require(Addresses.isValid(it.address)) { "Invalid address" }
            it.name?.let(Addresses::requireSafeHeaderValue)
        }
        Addresses.requireSafeHeaderValue(message.subject)
        require(messageIdShape.matches(message.messageId)) { "Invalid Message-ID" }
        message.inReplyTo?.let { require(messageIdShape.matches(it)) { "Invalid In-Reply-To" } }
        message.references.forEach { require(messageIdShape.matches(it)) { "Invalid References entry" } }

        val mime = FixedIdMessage(session, message.messageId).apply {
            setFrom(toInternet(message.from))
            setRecipients(Message.RecipientType.TO, message.to.map(::toInternet).toTypedArray())
            setRecipients(Message.RecipientType.CC, message.cc.map(::toInternet).toTypedArray())
            setRecipients(Message.RecipientType.BCC, message.bcc.map(::toInternet).toTypedArray())
            setSubject(message.subject, "UTF-8")
            sentDate = Date.from(message.sentAt)
            message.inReplyTo?.let { setHeader("In-Reply-To", it) }
            if (message.references.isNotEmpty()) setHeader("References", message.references.joinToString(" "))
            setText(message.text, "UTF-8")
            saveChanges()
        }
        val withBcc = ByteArrayOutputStream().also { mime.writeTo(it) }.toByteArray()
        val withoutBcc = ByteArrayOutputStream().also { mime.writeTo(it, arrayOf("Bcc")) }.toByteArray()
        return ComposedMessage(
            raw = withoutBcc,
            rawWithBcc = withBcc,
            envelopeFrom = message.from.address.trim(),
            recipients = everyone.map { it.address.trim() }.distinctBy(Addresses::normalize),
        )
    }

    private fun toInternet(entry: EmailAddress): InternetAddress = InternetAddress(entry.address.trim(), entry.name, "UTF-8")

    private class FixedIdMessage(session: Session, private val id: String) : MimeMessage(session) {
        override fun updateMessageID() {
            setHeader("Message-ID", id)
        }
    }
}
