package app.dustbird.mail.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeHtmlPolicyTest {
    private val themed = PageColors(background = 0xFF101010.toInt(), text = 0xFFEEEEEE.toInt(), link = 0xFF99D4B4.toInt())

    @Test
    fun onlyHttpHttpsMailtoAndTelLinksCanBeOpened() {
        listOf("http://example.com/", "HTTPS://Example.com/a?b=c&d=e", "mailto:user@example.com", "tel:+821012345678").forEach {
            assertTrue(it, LinkPolicy.canOpen(it))
        }
        listOf(
            "javascript:alert(1)", "JaVaScRiPt:alert(1)", " javascript:alert(1)", "java\nscript:alert(1)", "data:text/html,x", "file:///etc/passwd",
            "content://x/y", "intent://x#Intent;end", "vbscript:x", "about:blank", "blob:https://example.com/x", "market://details?id=x",
            "", "relative/path", "#fragment", "https://example.com/a b", "https://example.com/\u0000",
        ).forEach { assertFalse(it, LinkPolicy.canOpen(it)) }
    }

    @Test
    fun schemeIsReadCaseInsensitively() {
        assertEquals("https", LinkPolicy.schemeOf("HTTPS://example.com"))
        assertEquals("javascript", LinkPolicy.schemeOf("  JavaScript:alert(1)"))
        assertEquals(null, LinkPolicy.schemeOf("//example.com/x"))
    }

    @Test
    fun linkTextIsFlaggedOnlyWhenItLooksLikeADifferentAddress() {
        assertFalse(LinkPolicy.differs("example.com", "https://www.example.com/"))
        assertFalse(LinkPolicy.differs("user@example.com", "mailto:user@example.com"))
        assertTrue(LinkPolicy.differs("bank.example", "https://evil.example/login"))
        assertTrue(LinkPolicy.looksLikeAddress("bank.example"))
        assertFalse(LinkPolicy.looksLikeAddress("Click here."))
    }

    @Test
    fun imagesAreTheOnlySubResourcesThatCanPassAndOnlyOverHttps() {
        fun verdict(url: String, main: Boolean = false, images: Boolean = true) = ResourcePolicy.verdict(url, main, images)
        assertEquals(RequestVerdict.PASS, verdict("https://cdn.example/a.png"))
        assertEquals(RequestVerdict.BLOCK, verdict("https://cdn.example/a.png", images = false))
        assertEquals(RequestVerdict.BLOCK, verdict("http://cdn.example/a.png"))
        assertEquals(RequestVerdict.BLOCK, verdict("data:image/png;base64,AAAA"))
        assertEquals(RequestVerdict.BLOCK, verdict("ftp://cdn.example/a.png"))
        assertEquals(RequestVerdict.BLOCK, verdict("file:///etc/passwd"))
        assertEquals(RequestVerdict.BLOCK, verdict("javascript:alert(1)"))
    }

    @Test
    fun mainFrameOnlyAcceptsTheInlineDocument() {
        assertEquals(RequestVerdict.PASS, ResourcePolicy.verdict("data:text/html;charset=utf-8,x", true, false))
        assertEquals(RequestVerdict.PASS, ResourcePolicy.verdict("about:blank", true, false))
        assertEquals(RequestVerdict.BLOCK, ResourcePolicy.verdict("https://example.com/", true, true))
        assertEquals(RequestVerdict.BLOCK, ResourcePolicy.verdict(ResourcePolicy.FONT_URL, true, true))
    }

    @Test
    fun onlyTheExactInternalFontUrlIsServedLocally() {
        assertEquals(RequestVerdict.FONT, ResourcePolicy.verdict(ResourcePolicy.FONT_URL, false, false))
        assertEquals(RequestVerdict.BLOCK, ResourcePolicy.verdict(ResourcePolicy.FONT_URL + "?x=1", false, false))
        assertEquals(RequestVerdict.BLOCK, ResourcePolicy.verdict("https://fonts.dustbird.invalid/other.ttf", false, false))
    }

    @Test
    fun securityPolicyDropsHttpsImagesUnlessAllowed() {
        val blocked = SafeHtml.contentSecurityPolicy(false)
        val allowed = SafeHtml.contentSecurityPolicy(true)
        assertTrue(blocked.startsWith("default-src 'none'; img-src data:; style-src 'unsafe-inline'"))
        assertTrue(allowed.startsWith("default-src 'none'; img-src https: data:; style-src 'unsafe-inline'"))
        assertFalse(blocked.substringAfter("img-src").substringBefore(";").contains("https:"))
        listOf(blocked, allowed).forEach { assertTrue(it.endsWith("font-src ${ResourcePolicy.FONT_ORIGIN}")) }
    }

    @Test
    fun documentCarriesPolicyViewportAndBodyAndNothingExecutable() {
        val html = SafeHtml.page("<p>hello</p>", imagesAllowed = false, themed = themed).html
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertEquals(1, Regex("Content-Security-Policy").findAll(html).count())
        assertTrue(html.indexOf("Content-Security-Policy") < html.indexOf("<style>"))
        assertTrue(html.indexOf("Content-Security-Policy") < html.indexOf("<body>"))
        assertTrue(html.contains("""<meta name="viewport" content="width=device-width, initial-scale=1">"""))
        assertTrue(html.contains("<body><p>hello</p></body>"))
        assertFalse(html.contains("<script", ignoreCase = true))
        assertTrue(html.contains("font-family:Pretendard,sans-serif"))
        assertFalse(html.contains("!important"))
        assertTrue(html.contains("img{max-width:100%;height:auto}"))
        assertTrue(html.contains("table{max-width:100%}"))
    }

    @Test
    fun mailWithoutOwnColorsFollowsTheTheme() {
        val page = SafeHtml.page("<p>plain <b>text</b> color: red</p>", false, themed)
        assertEquals(themed.background, page.background)
        assertTrue(page.html.contains("color:#EEEEEE;background:#101010"))
        assertTrue(page.html.contains("a{color:#99D4B4}"))
    }

    @Test
    fun mailWithOwnColorsIsShownOnTheFixedLightPage() {
        listOf(
            "<p style=\"color: red\">x</p>", "<font color=\"red\">x</font>", "<td bgcolor=\"#fff\">x</td>",
            "<div style=\"font-size: 14px; background-color: #eee\">x</div>", "<p style='color:#000'>x</p>",
        ).forEach { body ->
            assertTrue(body, SafeHtml.hasOwnColors(body))
            val page = SafeHtml.page(body, false, themed)
            assertEquals(SafeHtml.lightPage.background, page.background)
            assertTrue(page.html.contains("color:#1A1A1A;background:#FFFFFF"))
        }
        assertFalse(SafeHtml.hasOwnColors("<div style=\"border-color: red; width: 10px\">x</div>"))
    }
}
