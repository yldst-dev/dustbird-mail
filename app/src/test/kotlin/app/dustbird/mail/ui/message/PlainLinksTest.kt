package app.dustbird.mail.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainLinksTest {
    private fun found(text: String) = PlainLinks.find(text).map { text.substring(it.start, it.end) to it.url }

    @Test
    fun webAddressesStopBeforeKoreanParticlesAndClosingPunctuation() {
        assertEquals(
            listOf("https://example.test/path?a=1&b=2" to "https://example.test/path?a=1&b=2"),
            found("주소: https://example.test/path?a=1&b=2에서 확인하세요."),
        )
        assertEquals(listOf("https://example.test/a" to "https://example.test/a"), found("(자세한 내용은 https://example.test/a)."))
        assertEquals(
            listOf("https://example.test/wiki/Mail_(client)" to "https://example.test/wiki/Mail_(client)"),
            found("참고 https://example.test/wiki/Mail_(client), 그리고"),
        )
    }

    @Test
    fun bareWwwAddressesOpenOverHttps() {
        assertEquals(listOf("www.example.test/help" to "https://www.example.test/help"), found("도움말은 www.example.test/help 를 보세요"))
    }

    @Test
    fun mailAddressesBecomeMailtoLinks() {
        assertEquals(
            listOf("help@partner.example" to "mailto:help@partner.example"),
            found("문의는 help@partner.example. 로 보내 주세요"),
        )
    }

    @Test
    fun addressInsideAWebAddressIsNotSplitOut() {
        assertEquals(
            listOf("https://example.test/u/me@example.test" to "https://example.test/u/me@example.test"),
            found("https://example.test/u/me@example.test"),
        )
    }

    @Test
    fun textWithoutLinksHasNoSpans() {
        assertTrue(found("링크가 없는 글입니다. 02-1588-1234, a.b, http 그리고 www.").isEmpty())
    }
}
