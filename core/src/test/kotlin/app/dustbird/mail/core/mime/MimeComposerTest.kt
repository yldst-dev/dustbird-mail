package app.dustbird.mail.core.mime

import app.dustbird.mail.core.model.EmailAddress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MimeComposerTest {
    private fun message(
        from: EmailAddress = EmailAddress("me@example.com", "홍길동"),
        to: List<EmailAddress> = listOf(EmailAddress("to@example.net", "받는 사람")),
        cc: List<EmailAddress> = listOf(EmailAddress("cc@example.net")),
        bcc: List<EmailAddress> = listOf(EmailAddress("hidden@example.net")),
        subject: String = "회의 일정 안내 – 11월 16일",
        text: String = "안녕하세요.\n본문 둘째 줄입니다.",
        messageId: String = "<id-1@example.com>",
        inReplyTo: String? = "<parent@example.net>",
        references: List<String> = listOf("<root@example.net>", "<parent@example.net>"),
    ) = OutgoingMessage(from, to, cc, bcc, subject, text, messageId, inReplyTo, references, Instant.parse("2026-11-16T09:00:00Z"))

    private fun String.headerBlock(): String = substringBefore("\r\n\r\n")

    @Test
    fun koreanSubjectNamesAndBodySurviveARoundTrip() {
        val composed = MimeComposer.compose(message())
        val parsed = MessageParser.parseRaw(composed.raw)
        val headers = MessageParser.headers(parsed, emptyList())
        assertEquals("회의 일정 안내 – 11월 16일", headers.subject)
        assertEquals("홍길동", headers.from.single().name)
        assertEquals("받는 사람", headers.to.single().name)
        val content = MessageParser.content(parsed)
        assertEquals("안녕하세요.\r\n본문 둘째 줄입니다.".replace("\r\n", "\n"), content.text!!.replace("\r\n", "\n"))
    }

    @Test
    fun headersContainOnlyAsciiBecauseNonAsciiIsEncoded() {
        val composed = MimeComposer.compose(message())
        val head = String(composed.raw, Charsets.ISO_8859_1).headerBlock()
        assertTrue(head.all { it.code < 0x80 })
        assertTrue(head.contains("=?UTF-8?"))
    }

    @Test
    fun messageIdAndThreadHeadersArePreservedExactly() {
        val parsed = MessageParser.parseRaw(MimeComposer.compose(message()).raw)
        val headers = MessageParser.headers(parsed, emptyList())
        assertEquals("<id-1@example.com>", headers.messageId)
        assertEquals("<parent@example.net>", headers.inReplyTo)
        assertEquals(listOf("<root@example.net>", "<parent@example.net>"), headers.references)
        assertEquals(Instant.parse("2026-11-16T09:00:00Z"), headers.sentAt)
    }

    @Test
    fun bccIsAbsentFromTheTransmittedCopyButKeptInTheSentCopy() {
        val composed = MimeComposer.compose(message())
        val transmitted = String(composed.raw, Charsets.ISO_8859_1)
        val stored = String(composed.rawWithBcc, Charsets.ISO_8859_1)
        assertFalse(transmitted.contains("hidden@example.net"))
        assertFalse(transmitted.headerBlock().contains("Bcc", ignoreCase = true))
        assertTrue(stored.headerBlock().contains("Bcc:"))
        assertEquals(listOf("to@example.net", "cc@example.net", "hidden@example.net"), composed.recipients)
        assertEquals("me@example.com", composed.envelopeFrom)
    }

    @Test
    fun duplicateRecipientsAreSentOnce() {
        val composed = MimeComposer.compose(message(to = listOf(EmailAddress("A@example.net")), cc = listOf(EmailAddress("a@example.net")), bcc = emptyList()))
        assertEquals(listOf("A@example.net"), composed.recipients)
    }

    @Test
    fun replyWithoutThreadHeadersOmitsThem() {
        val head = String(MimeComposer.compose(message(inReplyTo = null, references = emptyList())).raw, Charsets.ISO_8859_1).headerBlock()
        assertFalse(head.contains("In-Reply-To"))
        assertFalse(head.contains("References"))
    }

    @Test
    fun headerInjectionAttemptsAreRejected() {
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(subject = "hello\r\nBcc: evil@example.net")) }
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(from = EmailAddress("me@example.com", "name\r\nX-Evil: 1"))) }
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(to = listOf(EmailAddress("to@example.net\r\nBcc: evil@example.net")))) }
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(to = listOf(EmailAddress("to@example.net", "a\nb")))) }
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(messageId = "<a@b>\r\nX: y")) }
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(inReplyTo = "<a b@c>")) }
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(references = listOf("not-an-id"))) }
    }

    @Test
    fun invalidOrMissingRecipientsAreRejected() {
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(to = emptyList(), cc = emptyList(), bcc = emptyList())) }
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(to = listOf(EmailAddress("broken")))) }
        assertFailsWith<IllegalArgumentException> { MimeComposer.compose(message(from = EmailAddress("broken"))) }
    }

    @Test
    fun bodyIsDeclaredAsUtf8Text() {
        val head = String(MimeComposer.compose(message()).raw, Charsets.ISO_8859_1).headerBlock()
        assertTrue(head.contains("charset=UTF-8", ignoreCase = true))
        assertTrue(head.contains("MIME-Version: 1.0"))
    }

    @Test
    fun draftsMayBeIncompleteButStillRejectHeaderInjection() {
        val draft = MimeComposer.composeDraft(message(to = listOf(EmailAddress("typing-not-finished")), cc = emptyList(), bcc = emptyList(), subject = ""))
        assertTrue(draft.recipients.isEmpty())
        assertFailsWith<IllegalArgumentException> { MimeComposer.composeDraft(message(subject = "a\r\nBcc: x@y.com")) }
    }
}
