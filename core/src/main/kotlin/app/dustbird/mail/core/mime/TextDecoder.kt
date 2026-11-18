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
    private val eucKr: Charset? = find("EUC-KR")
    private val encodedWord = Regex("=\\?[^?\\s]+\\?[BbQq]\\?[^?\\s]*\\?=")

    init {
        MimeTolerance.ensure()
    }

    fun decode(bytes: ByteArray, declared: String?): String {
        if (bytes.isEmpty()) return ""
        val name = declared?.trim()?.trim('"')?.lowercase()
        val declaredCharset = name?.let(::find)
        if (name != null && name in latinNames) {
            return strict(bytes, utf8) ?: mislabeledKorean(bytes) ?: String(bytes, declaredCharset ?: Charsets.ISO_8859_1)
        }
        val order: List<Charset> = when {
            name != null && name in koreanNames -> listOfNotNull(cp949, utf8)
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
            val decoded = try {
                MimeUtility.decodeText(unfolded)
            } catch (_: Exception) {
                unfolded
            }
            return encodedWord.replace(decoded) { match ->
                try {
                    MimeUtility.decodeWord(match.value)
                } catch (_: Exception) {
                    match.value
                }
            }
        }
        val needsRepair = unfolded.any { it.code in 0x80..0xFF } && unfolded.none { it.code > 0xFF }
        return if (needsRepair) decode(unfolded.toByteArray(Charsets.ISO_8859_1), null) else unfolded
    }

    private fun mislabeledKorean(bytes: ByteArray): String? {
        val text = eucKr?.let { strict(bytes, it) } ?: return null
        val wide = text.count { it.code > 0x7F }
        return text.takeIf { wide > 0 && text.count { it in '\uAC00'..'\uD7A3' } * 10 >= wide * 7 }
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
