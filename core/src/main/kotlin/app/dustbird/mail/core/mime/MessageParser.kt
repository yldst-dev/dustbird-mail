package app.dustbird.mail.core.mime

import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.model.EmailAddress
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.util.Properties
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.ContentType
import javax.mail.internet.MimeMessage
import org.jsoup.Jsoup

data class AttachmentInfo(val fileName: String?, val mimeType: String, val size: Int)

data class ParsedContent(val text: String?, val html: String?, val attachments: List<AttachmentInfo>)

data class ParsedHeaders(
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    val from: List<EmailAddress>,
    val replyTo: List<EmailAddress>,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val subject: String,
    val sentAt: Instant?,
    val deliveryHeaderValues: List<String>,
    val contentType: String?,
)

object MessageParser {
    private val session: Session = Session.getInstance(Properties().apply {
        put("mail.mime.allowutf8", "false")
        put("mail.mime.decodefilename", "true")
        put("mail.mime.multipart.ignoremissingendboundary", "true")
        put("mail.mime.multipart.ignoremissingboundaryparameter", "true")
    })
    private val idPattern = Regex("<[^<>\\s]+>")

    fun parseRaw(bytes: ByteArray): MimeMessage = MimeMessage(session, ByteArrayInputStream(bytes))

    fun headers(message: MimeMessage, deliveryHeaders: List<String>): ParsedHeaders {
        fun raw(name: String): String? = message.getHeader(name, " ")
        fun decoded(name: String): String = TextDecoder.decodeHeader(raw(name))
        val delivery = deliveryHeaders.flatMap { name -> message.getHeader(name)?.toList().orEmpty() }
        return ParsedHeaders(
            messageId = idPattern.find(raw("Message-ID").orEmpty())?.value,
            inReplyTo = idPattern.find(raw("In-Reply-To").orEmpty())?.value,
            references = idPattern.findAll(raw("References").orEmpty()).map { it.value }.toList(),
            from = Addresses.parseList(decoded("From")),
            replyTo = Addresses.parseList(decoded("Reply-To")),
            to = Addresses.parseList(decoded("To")),
            cc = Addresses.parseList(decoded("Cc")),
            subject = decoded("Subject"),
            sentAt = try { message.sentDate?.toInstant() } catch (_: Exception) { null },
            deliveryHeaderValues = delivery.map { TextDecoder.decodeHeader(it) },
            contentType = raw("Content-Type")?.lowercase(),
        )
    }

    fun content(part: Part): ParsedContent {
        val text = StringBuilder()
        val html = StringBuilder()
        val attachments = mutableListOf<AttachmentInfo>()
        try {
            walk(part, text, html, attachments)
        } catch (_: MessagingException) {
        } catch (_: IOException) {
        }
        return ParsedContent(text.toString().ifBlank { null }, html.toString().ifBlank { null }, attachments)
    }

    fun plainText(content: ParsedContent): String =
        content.text ?: content.html?.let { Jsoup.parse(it).wholeText().trim() } ?: ""

    fun preview(content: ParsedContent, limit: Int = 200): String {
        val source = content.text ?: content.html?.let { Jsoup.parse(it).text() } ?: return ""
        return source.lineSequence()
            .filterNot { it.trimStart().startsWith(">") }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(limit)
    }

    private fun walk(part: Part, text: StringBuilder, html: StringBuilder, attachments: MutableList<AttachmentInfo>) {
        val mimeType = baseType(part)
        if (mimeType.startsWith("multipart/")) {
            val multipart = part.content as? Multipart ?: return
            for (index in 0 until multipart.count) walk(multipart.getBodyPart(index), text, html, attachments)
            return
        }
        val fileName = try { part.fileName?.let(TextDecoder::decodeHeader) } catch (_: Exception) { null }
        val disposition = try { part.disposition?.lowercase() } catch (_: Exception) { null }
        val isAttachment = disposition == Part.ATTACHMENT || fileName != null || mimeType == "message/rfc822"
        if (isAttachment) {
            attachments += AttachmentInfo(fileName, mimeType, part.size)
            return
        }
        when (mimeType) {
            "text/plain" -> append(text, readText(part))
            "text/html" -> append(html, readText(part))
        }
    }

    private fun append(target: StringBuilder, value: String) {
        if (value.isBlank()) return
        if (target.isNotEmpty()) target.append("\n\n")
        target.append(value)
    }

    private fun readText(part: Part): String {
        val bytes = part.inputStream.use { it.readBytes() }
        return TextDecoder.decode(bytes, charsetOf(part))
    }

    private fun baseType(part: Part): String = try {
        ContentType(part.contentType).baseType.lowercase()
    } catch (_: Exception) {
        "text/plain"
    }

    private fun charsetOf(part: Part): String? = try {
        ContentType(part.contentType).getParameter("charset")
    } catch (_: Exception) {
        null
    }
}
