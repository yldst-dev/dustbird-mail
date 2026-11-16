package app.dustbird.mail.core.mime

import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {
    private val korean = "안녕하세요 한글 메일입니다"
    private val cp949 = charset("x-windows-949")
    private val eucKr = charset("EUC-KR")

    private fun mail(headers: List<String>, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((headers.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun textMail(charsetName: String, body: ByteArray, encoding: String = "8bit", extra: List<String> = emptyList()) = mail(
        listOf("From: a@example.com", "To: b@example.com", "Subject: t", "MIME-Version: 1.0", "Content-Type: text/plain; charset=$charsetName", "Content-Transfer-Encoding: $encoding") + extra,
        body,
    )

    private fun text(raw: ByteArray): String? = MessageParser.content(MessageParser.parseRaw(raw)).text

    @Test
    fun eucKrBodyIsDecoded() {
        assertEquals(korean, text(textMail("EUC-KR", korean.toByteArray(eucKr)))?.trim())
    }

    @Test
    fun cp949OnlyCharactersDecodeWhenDeclaredAsEucKr() {
        val sample = "똠방각하 쀍"
        assertEquals(sample, text(textMail("euc-kr", sample.toByteArray(cp949)))?.trim())
    }

    @Test
    fun ksC5601AliasAndBase64TransferAreDecoded() {
        val encoded = Base64.getMimeEncoder().encodeToString(korean.toByteArray(eucKr)).toByteArray()
        assertEquals(korean, text(textMail("ks_c_5601-1987", encoded, "base64"))?.trim())
    }

    @Test
    fun declaredUtf8ButActuallyCp949IsRecovered() {
        assertEquals(korean, text(textMail("UTF-8", korean.toByteArray(cp949)))?.trim())
    }

    @Test
    fun declaredAsciiButActuallyUtf8IsRecovered() {
        assertEquals(korean, text(textMail("us-ascii", korean.toByteArray(Charsets.UTF_8)))?.trim())
        assertEquals(korean, text(textMail("iso-8859-1", korean.toByteArray(Charsets.UTF_8)))?.trim())
    }

    @Test
    fun genuineLatinTextStaysLatin() {
        assertEquals("café déjà vu", text(textMail("iso-8859-1", "café déjà vu".toByteArray(Charsets.ISO_8859_1)))?.trim())
    }

    @Test
    fun unknownCharsetAndGarbageBytesNeverCrash() {
        assertEquals(korean, text(textMail("x-no-such-charset", korean.toByteArray(Charsets.UTF_8)))?.trim())
        assertNotNull(text(textMail("UTF-8", byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x41, 0x0A))))
        assertNotNull(TextDecoder.decode(byteArrayOf(0x80.toByte(), 0xC3.toByte()), "shift_jis"))
        assertEquals("", TextDecoder.decode(ByteArray(0), "utf-8"))
    }

    @Test
    fun encodedWordSubjectsInSeveralCharsetsAreDecoded() {
        val b = Base64.getEncoder().encodeToString(korean.toByteArray(eucKr))
        assertEquals(korean, TextDecoder.decodeHeader("=?EUC-KR?B?$b?="))
        val u = Base64.getEncoder().encodeToString(korean.toByteArray(Charsets.UTF_8))
        assertEquals(korean, TextDecoder.decodeHeader("=?UTF-8?B?$u?="))
        assertEquals("안녕", TextDecoder.decodeHeader("=?UTF-8?Q?=EC=95=88=EB=85=95?="))
    }

    @Test
    fun rawEightBitSubjectsAreRepairedWhetherUtf8OrCp949() {
        assertEquals(korean, TextDecoder.decodeHeader(String(korean.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)))
        assertEquals(korean, TextDecoder.decodeHeader(String(korean.toByteArray(cp949), Charsets.ISO_8859_1)))
    }

    @Test
    fun unknownEncodedWordCharsetFallsBackToTheRawText() {
        assertEquals("=?x-bogus?B?QUJD?=", TextDecoder.decodeHeader("=?x-bogus?B?QUJD?="))
    }

    @Test
    fun foldedHeadersAreUnfolded() {
        assertEquals("first second", TextDecoder.decodeHeader("first\r\n second"))
    }

    @Test
    fun headersAreExtractedWithDeliveryEvidence() {
        val raw = mail(
            listOf(
                "Message-ID: <m1@example.com>",
                "In-Reply-To: <p@example.com>",
                "References: <a@example.com> <p@example.com>",
                "From: =?UTF-8?B?7ZmN6ri464+Z?= <hong@example.com>",
                "Reply-To: reply@example.com",
                "To: biz@example.com, other@example.com",
                "Cc: c@example.com",
                "Delivered-To: biz@example.com",
                "X-Original-To: me@example.com",
                "Subject: =?UTF-8?B?7JWI64WV?=",
                "Date: Mon, 16 Nov 2026 18:00:00 +0900",
                "Content-Type: multipart/mixed; boundary=zz",
            ),
            "--zz--\r\n".toByteArray(),
        )
        val headers = MessageParser.headers(MessageParser.parseRaw(raw), listOf("Delivered-To", "X-Original-To"))
        assertEquals("<m1@example.com>", headers.messageId)
        assertEquals("<p@example.com>", headers.inReplyTo)
        assertEquals(listOf("<a@example.com>", "<p@example.com>"), headers.references)
        assertEquals("홍길동", headers.from.single().name)
        assertEquals("reply@example.com", headers.replyTo.single().address)
        assertEquals(listOf("biz@example.com", "other@example.com"), headers.to.map { it.address })
        assertEquals(listOf("biz@example.com", "me@example.com"), headers.deliveryHeaderValues)
        assertEquals("안녕", headers.subject)
        assertNotNull(headers.sentAt)
        assertTrue(headers.contentType!!.startsWith("multipart/mixed"))
    }

    @Test
    fun missingOrBrokenHeadersDoNotThrow() {
        val headers = MessageParser.headers(MessageParser.parseRaw("Subject: x\r\nDate: not a date\r\n\r\nbody".toByteArray()), emptyList())
        assertEquals("x", headers.subject)
        assertNull(headers.messageId)
        assertNull(headers.sentAt)
        assertTrue(headers.from.isEmpty())
    }

    private fun multipart(type: String, boundary: String, vararg parts: String) = mail(
        listOf("From: a@example.com", "To: b@example.com", "Subject: s", "MIME-Version: 1.0", "Content-Type: $type; boundary=$boundary"),
        (parts.joinToString("") { "--$boundary\r\n$it\r\n" } + "--$boundary--\r\n").toByteArray(Charsets.UTF_8),
    )

    @Test
    fun alternativeBodiesProduceTextAndHtmlWithoutDuplication() {
        val raw = multipart(
            "multipart/alternative", "b1",
            "Content-Type: text/plain; charset=UTF-8\r\n\r\n평문 본문",
            "Content-Type: text/html; charset=UTF-8\r\n\r\n<p>HTML 본문</p>",
        )
        val content = MessageParser.content(MessageParser.parseRaw(raw))
        assertEquals("평문 본문", content.text)
        assertEquals("<p>HTML 본문</p>", content.html)
        assertTrue(content.attachments.isEmpty())
    }

    @Test
    fun attachmentsAreListedByNameWithoutBeingReadAsBody() {
        val raw = multipart(
            "multipart/mixed", "b2",
            "Content-Type: text/plain; charset=UTF-8\r\n\r\n본문",
            "Content-Type: application/pdf; name=\"report.pdf\"\r\nContent-Disposition: attachment; filename=\"report.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0=",
            "Content-Type: text/plain\r\nContent-Disposition: attachment; filename*=UTF-8''%ED%95%9C%EA%B8%80.txt\r\n\r\nsecret attachment text",
            "Content-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"=?UTF-8?B?7J2066aE?=.bin\"\r\n\r\nxx",
        )
        val content = MessageParser.content(MessageParser.parseRaw(raw))
        assertEquals("본문", content.text)
        assertEquals(listOf("report.pdf", "한글.txt", "이름.bin"), content.attachments.map { it.fileName })
        assertTrue(content.text!!.contains("secret").not())
    }

    @Test
    fun truncatedAndMalformedStructuresStillYieldWhatIsReadable() {
        val truncated = mail(
            listOf("From: a@example.com", "Subject: s", "MIME-Version: 1.0", "Content-Type: multipart/mixed; boundary=cut"),
            "--cut\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n잘린 메일 본문\r\n".toByteArray(),
        )
        assertEquals("잘린 메일 본문", MessageParser.content(MessageParser.parseRaw(truncated)).text?.trim())
        val broken = mail(listOf("Subject: s", "Content-Type: ;;;garbage"), "plain body".toByteArray())
        assertEquals("plain body", MessageParser.content(MessageParser.parseRaw(broken)).text)
    }

    @Test
    fun previewSkipsQuotesCollapsesWhitespaceAndIsLimited() {
        val content = ParsedContent("첫 줄\n> 인용된 줄\n\n   둘째    줄\n" + "x".repeat(400), null, emptyList())
        val preview = MessageParser.preview(content)
        assertTrue(preview.startsWith("첫 줄 둘째 줄 xxx"))
        assertEquals(200, preview.length)
        assertEquals("제목 본문", MessageParser.preview(ParsedContent(null, "<h1>제목</h1><p>본문</p>", emptyList())))
        assertEquals("", MessageParser.preview(ParsedContent(null, null, emptyList())))
    }
}
