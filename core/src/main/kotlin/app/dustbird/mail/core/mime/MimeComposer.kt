package app.dustbird.mail.core.mime

import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.model.EmailAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.Message
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility

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
    val attachments: List<OutgoingAttachment> = emptyList(),
)

data class OutgoingAttachment(val fileName: String, val mimeType: String, val file: File)

class ComposedMessage(
    val raw: ByteArray,
    val rawWithBcc: ByteArray,
    val envelopeFrom: String,
    val recipients: List<String>,
)

object MimeComposer {
    private val session: Session = Session.getInstance(Properties().apply { put("mail.mime.allowutf8", "false") })
    private val messageIdShape = Regex("^<[^<>\\s@]+@[^<>\\s@]+>$")
    private val referenceShape = Regex("^<[^<>\\s]+>$")

    fun compose(message: OutgoingMessage): ComposedMessage = build(message, strict = true)

    fun composeDraft(message: OutgoingMessage): ComposedMessage = build(
        message.copy(
            to = message.to.filter { Addresses.isValid(it.address) },
            cc = message.cc.filter { Addresses.isValid(it.address) },
            bcc = message.bcc.filter { Addresses.isValid(it.address) },
        ),
        strict = false,
    )

    fun withoutBcc(rawWithBcc: ByteArray): ByteArray =
        ByteArrayOutputStream().also { MimeMessage(session, ByteArrayInputStream(rawWithBcc)).writeTo(it, arrayOf("Bcc")) }.toByteArray()

    private fun build(message: OutgoingMessage, strict: Boolean): ComposedMessage {
        val everyone = message.to + message.cc + message.bcc
        if (strict) require(everyone.isNotEmpty()) { "At least one recipient is required" }
        (everyone + message.from).forEach {
            require(Addresses.isValid(it.address)) { "Invalid address" }
            it.name?.let(Addresses::requireSafeHeaderValue)
        }
        Addresses.requireSafeHeaderValue(message.subject)
        require(messageIdShape.matches(message.messageId)) { "Invalid Message-ID" }
        message.inReplyTo?.let { require(referenceShape.matches(it)) { "Invalid In-Reply-To" } }
        message.references.forEach { require(referenceShape.matches(it)) { "Invalid References entry" } }

        val mime = FixedIdMessage(session, message.messageId).apply {
            setFrom(toInternet(message.from))
            setRecipients(Message.RecipientType.TO, message.to.map(::toInternet).toTypedArray())
            setRecipients(Message.RecipientType.CC, message.cc.map(::toInternet).toTypedArray())
            setRecipients(Message.RecipientType.BCC, message.bcc.map(::toInternet).toTypedArray())
            setSubject(message.subject, "UTF-8")
            sentDate = Date.from(message.sentAt)
            message.inReplyTo?.let { setHeader("In-Reply-To", it) }
            if (message.references.isNotEmpty()) setHeader("References", MimeUtility.fold(12, message.references.joinToString(" ")))
            if (message.attachments.isEmpty()) {
                setText(message.text, "UTF-8")
            } else {
                val mixed = MimeMultipart("mixed")
                mixed.addBodyPart(MimeBodyPart().apply { setText(message.text, "UTF-8") })
                message.attachments.forEach { attachment ->
                    Addresses.requireSafeHeaderValue(attachment.fileName)
                    Addresses.requireSafeHeaderValue(attachment.mimeType)
                    mixed.addBodyPart(
                        MimeBodyPart().apply {
                            dataHandler = DataHandler(FileSource(attachment))
                            fileName = attachment.fileName
                            disposition = Part.ATTACHMENT
                        },
                    )
                }
                setContent(mixed)
            }
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

    private class FileSource(private val attachment: OutgoingAttachment) : DataSource {
        override fun getInputStream(): InputStream = FileInputStream(attachment.file)
        override fun getOutputStream(): OutputStream = throw IOException("read only")
        override fun getContentType(): String = attachment.mimeType
        override fun getName(): String = attachment.fileName
    }

    private class FixedIdMessage(session: Session, private val id: String) : MimeMessage(session) {
        override fun updateMessageID() {
            setHeader("Message-ID", id)
        }
    }
}
