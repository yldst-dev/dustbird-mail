package app.dustbird.mail.core.identity

import app.dustbird.mail.core.model.EmailAddress
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress

object Addresses {
    private val local = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}$")
    private val label = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    fun normalize(address: String): String = address.trim().lowercase()

    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

    fun isValid(address: String): Boolean {
        if (!isSafeHeaderValue(address)) return false
        val value = address.trim()
        if (value.length > 254 || value.count { it == '@' } != 1) return false
        val (localPart, domain) = value.split('@')
        if (!local.matches(localPart) || localPart.startsWith('.') || localPart.endsWith('.') || ".." in localPart) return false
        val labels = domain.split('.')
        return labels.size >= 2 && labels.all { label.matches(it) }
    }

    fun isSafeHeaderValue(value: String): Boolean = value.none { it == '\r' || it == '\n' || it == '\u0000' }

    fun requireSafeHeaderValue(value: String): String {
        require(isSafeHeaderValue(value)) { "Header value contains a line break or NUL" }
        return value
    }

    private fun looksLikeAddress(value: String): Boolean =
        value.count { it == '@' } == 1 && !value.startsWith('@') && !value.endsWith('@') &&
            value.none { it.isWhitespace() || it.isISOControl() || it == '<' || it == '>' }

    fun parseList(header: String?): List<EmailAddress> {
        if (header.isNullOrBlank()) return emptyList()
        val parsed = try {
            InternetAddress.parseHeader(header, false)
        } catch (_: AddressException) {
            return emptyList()
        }
        return parsed.mapNotNull { entry ->
            val address = entry.address?.trim().orEmpty()
            if (!looksLikeAddress(address)) null else EmailAddress(address, entry.personal?.takeIf { it.isNotBlank() })
        }
    }
}
