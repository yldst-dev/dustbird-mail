package app.dustbird.mail.core.identity

import app.dustbird.mail.core.model.EmailAddress
import java.net.URLDecoder

data class MailtoData(
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>,
    val subject: String,
    val body: String,
)

object Mailto {
    fun parse(uri: String): MailtoData? {
        if (!uri.startsWith("mailto:", ignoreCase = true)) return null
        val rest = uri.substring(7).substringBefore('#')
        val to = mutableListOf(decode(rest.substringBefore('?')))
        val cc = mutableListOf<String>()
        val bcc = mutableListOf<String>()
        var subject = ""
        var body = ""
        rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val value = decode(pair.substringAfter('=', ""))
            when (decode(pair.substringBefore('=')).lowercase()) {
                "to" -> to += value
                "cc" -> cc += value
                "bcc" -> bcc += value
                "subject" -> subject = value
                "body" -> body = value
            }
        }
        return MailtoData(
            to = addresses(to),
            cc = addresses(cc),
            bcc = addresses(bcc),
            subject = subject.replace(Regex("[\\r\\n\\u0000]+"), " ").trim(),
            body = body.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", ""),
        )
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }

    private fun addresses(values: List<String>): List<EmailAddress> = values
        .flatMap { Addresses.parseList(it) }
        .filter { Addresses.isValid(it.address) }
        .distinctBy { Addresses.normalize(it.address) }
}
