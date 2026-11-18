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
import javax.mail.internet.MimePart
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

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
    private val lineBreaking = setOf("p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "table", "ul", "ol", "pre", "hr")
    private val simpleSubtype = Regex("[a-z0-9]{2,5}")

    init {
        MimeTolerance.ensure()
    }

    fun parseRaw(bytes: ByteArray): MimeMessage = MimeMessage(session, ByteArrayInputStream(bytes))

    fun headers(message: MimeMessage, deliveryHeaders: List<String>): ParsedHeaders {
        fun raw(name: String): String? = message.getHeader(name, " ")
        fun decoded(name: String): String = TextDecoder.decodeHeader(raw(name))
        fun addresses(name: String): List<EmailAddress> {
            val value = raw(name) ?: return emptyList()
            val repair = "=?" !in value
            return Addresses.parseList(value).map { entry ->
                val shown = entry.name?.let { if (repair) TextDecoder.decodeHeader(it) else it }?.trim()
                entry.copy(name = shown?.takeIf { it.isNotEmpty() && ('@' !in it || it.contains(entry.address, ignoreCase = true)) })
            }
        }
        val delivery = deliveryHeaders.flatMap { name -> message.getHeader(name)?.toList().orEmpty() }
        return ParsedHeaders(
            messageId = idPattern.find(raw("Message-ID").orEmpty())?.value,
            inReplyTo = idPattern.find(raw("In-Reply-To").orEmpty())?.value,
            references = idPattern.findAll(raw("References").orEmpty()).map { it.value }.toList(),
            from = addresses("From"),
            replyTo = addresses("Reply-To"),
            to = addresses("To"),
            cc = addresses("Cc"),
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

    fun contentStrict(part: Part): ParsedContent {
        val text = StringBuilder()
        val html = StringBuilder()
        val attachments = mutableListOf<AttachmentInfo>()
        walk(part, text, html, attachments)
        return ParsedContent(text.toString().ifBlank { null }, html.toString().ifBlank { null }, attachments)
    }

    fun inReplyTo(raw: ByteArray): String? = headers(parseRaw(raw), emptyList()).inReplyTo

    fun attachmentPart(part: Part, index: Int): Part? {
        val found = ArrayList<Part>()
        collectAttachments(part, found)
        return found.getOrNull(index)
    }

    private fun collectAttachments(part: Part, found: MutableList<Part>) {
        if (baseType(part).startsWith("multipart/")) {
            val multipart = part.content as? Multipart ?: return
            for (index in 0 until multipart.count) collectAttachments(multipart.getBodyPart(index), found)
        } else if (isAttachment(part)) {
            found += part
        }
    }

    private fun isAttachment(part: Part): Boolean {
        val fileName = try { part.fileName } catch (_: Exception) { null }
        val disposition = try { part.disposition?.lowercase() } catch (_: Exception) { null }
        val type = baseType(part)
        return disposition == Part.ATTACHMENT || fileName != null || type == "text/calendar" || !type.startsWith("text/")
    }

    private fun defaultName(mimeType: String): String? = when {
        mimeType == "message/rfc822" -> "message.eml"
        mimeType == "text/calendar" -> "invite.ics"
        mimeType == "application/pdf" -> "attachment.pdf"
        mimeType.substringBefore('/') in setOf("image", "audio", "video") ->
            mimeType.substringAfter('/').takeIf(simpleSubtype::matches)?.let { "${mimeType.substringBefore('/')}.$it" }
        else -> null
    }

    fun plainText(content: ParsedContent): String = content.text ?: content.html?.let(::htmlToText) ?: ""

    fun htmlToText(html: String): String {
        val document = Jsoup.parse(html)
        document.select("script, style, head, title").remove()
        val out = StringBuilder()
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when {
                        node is TextNode -> out.append(node.text())
                        node is Element && node.normalName() == "br" -> out.append('\n')
                        node is Element && node.normalName() in lineBreaking -> breakLine()
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.normalName() in lineBreaking) breakLine()
                }

                private fun breakLine() {
                    if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                }
            },
            document.body(),
        )
        return out.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

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
        if (isAttachment(part)) {
            val fileName = try { part.fileName?.let(TextDecoder::decodeHeader) } catch (_: Exception) { null }
            val base64 = try { (part as? MimePart)?.encoding?.equals("base64", ignoreCase = true) == true } catch (_: Exception) { false }
            val size = if (base64 && part.size > 0) part.size / 4 * 3 else part.size
            attachments += AttachmentInfo(fileName?.takeIf { it.isNotBlank() } ?: defaultName(mimeType), mimeType, size)
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
