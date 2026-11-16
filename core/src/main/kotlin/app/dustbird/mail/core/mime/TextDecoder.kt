package app.dustbird.mail.core.mime

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.mail.internet.MimeUtility

object TextDecoder {
    private val utf8: Charset = Charsets.UTF_8
    private val cp949: Charset? = listOf("x-windows-949", "MS949", "windows-949", "EUC-KR").firstNotNullOfOrNull(::find)
    private val koreanNames = setOf("euc-kr", "euckr", "ks_c_5601-1987", "ks_c_5601", "cp949", "ms949", "x-windows-949", "windows-949", "uhc")
    private val latinNames = setOf("us-ascii", "ascii", "iso-8859-1", "latin1", "windows-1252", "cp1252", "iso-8859-15")

    fun decode(bytes: ByteArray, declared: String?): String {
        if (bytes.isEmpty()) return ""
        val name = declared?.trim()?.trim('"')?.lowercase()
        val declaredCharset = name?.let(::find)
        val order: List<Charset> = when {
            name != null && name in koreanNames -> listOfNotNull(cp949, utf8)
            name != null && name in latinNames -> listOfNotNull(utf8, cp949, declaredCharset)
            name == "utf-8" || name == "utf8" -> listOfNotNull(utf8, cp949)
            declaredCharset != null -> listOf(declaredCharset, utf8)
            else -> listOfNotNull(utf8, cp949)
        }
        for (charset in order) strict(bytes, charset)?.let { return it }
        return String(bytes, declaredCharset ?: utf8)
    }

    fun decodeHeader(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val unfolded = raw.replace(Regex("\r?\n[ \t]+"), " ").trim()
        if ("=?" in unfolded) {
            return try {
                MimeUtility.decodeText(unfolded)
            } catch (_: Exception) {
                unfolded
            }
        }
        val needsRepair = unfolded.any { it.code in 0x80..0xFF } && unfolded.none { it.code > 0xFF }
        return if (needsRepair) decode(unfolded.toByteArray(Charsets.ISO_8859_1), null) else unfolded
    }

    private fun find(name: String): Charset? = try {
        if (Charset.isSupported(name)) Charset.forName(name) else null
    } catch (_: Exception) {
        null
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }
}
