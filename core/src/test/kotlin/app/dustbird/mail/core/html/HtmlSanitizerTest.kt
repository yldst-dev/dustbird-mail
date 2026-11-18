package app.dustbird.mail.core.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlSanitizerTest {
    private fun clean(html: String, images: Boolean = false) = HtmlSanitizer.sanitize(html, images)

    @Test
    fun scriptsAndEventHandlersAreRemoved() {
        val out = clean("<p onclick=\"alert(1)\" onmouseover=\"x()\">hi</p><script>alert(2)</script><img src=x onerror=\"alert(3)\">").body
        assertFalse(out.contains("script", true))
        assertFalse(out.contains("onclick", true))
        assertFalse(out.contains("onerror", true))
        assertFalse(out.contains("alert"))
        assertTrue(out.contains("hi"))
    }

    @Test
    fun dangerousElementsAreDropped() {
        val html = "<iframe src=//evil></iframe><object data=x></object><embed src=x><form action=//evil><input name=a></form>" +
            "<meta http-equiv=refresh content=\"0;url=//evil\"><link rel=stylesheet href=//evil><base href=//evil>" +
            "<svg onload=alert(1)><circle/></svg><style>@import url(//evil);</style><video src=x></video><p>kept</p>"
        val out = clean(html).body
        listOf("iframe", "object", "embed", "form", "input", "meta", "link", "base", "svg", "style", "video", "evil").forEach { assertFalse(out.contains(it, true), it) }
        assertTrue(out.contains("kept"))
    }

    @Test
    fun onlySafeLinkSchemesSurviveAndAnchorsAreHardened() {
        val out = clean(
            "<a href=\"javascript:alert(1)\">a</a><a href=\"JaVaScRiPt:alert(1)\">b</a><a href=\"data:text/html,x\">c</a><a href=\"file:///etc/passwd\">d</a>" +
                "<a href=\"https://example.com/x\" target=\"_blank\">e</a><a href=\"mailto:me@example.com\">f</a><a href=\"tel:+821012345678\">g</a>",
        ).body
        assertFalse(out.contains("javascript", true))
        assertFalse(out.contains("data:", true))
        assertFalse(out.contains("file:", true))
        assertTrue(out.contains("href=\"https://example.com/x\""))
        assertTrue(out.contains("mailto:me@example.com"))
        assertTrue(out.contains("tel:+821012345678"))
        assertTrue(out.contains("rel=\"noopener noreferrer nofollow\""))
        assertFalse(out.contains("target="))
    }

    @Test
    fun remoteImagesAreBlockedByDefaultAndCounted() {
        val result = clean("<p>text</p><img src=\"https://tracker.example/p.gif\" alt=\"\"><img src=\"http://insecure.example/a.png\" alt=\"logo\">")
        assertFalse(result.body.contains("tracker.example"))
        assertFalse(result.body.contains("insecure.example"))
        assertFalse(result.body.contains("<img"))
        assertTrue(result.body.contains("logo"))
        assertEquals(1, result.blockedRemoteImages)
        assertEquals(1, result.unshownImages)
    }

    @Test
    fun allowingImagesKeepsOnlyHttps() {
        val result = clean("<img src=\"https://ok.example/a.png\"><img src=\"http://no.example/a.png\"><img src=\"data:image/png;base64,AAAA\"><img src=\"cid:inline1\">", images = true)
        assertTrue(result.body.contains("https://ok.example/a.png"))
        assertFalse(result.body.contains("no.example"))
        assertFalse(result.body.contains("data:image"))
        assertFalse(result.body.contains("cid:"))
        assertEquals(0, result.blockedRemoteImages)
        assertEquals(3, result.unshownImages)
    }

    @Test
    fun inlineStylesAreFilteredByAllowlist() {
        val out = clean(
            "<div style=\"color: red; background: url(http://evil/x.png); position: fixed; width: 100px; background-color: expression(alert(1)); font-size: 14px\">x</div>" +
                "<p style=\"behavior:url(x.htc)\">y</p><span style=\"color:red\\3b background:blue\">z</span>",
        ).body
        assertTrue(out.contains("color: red"))
        assertTrue(out.contains("width: 100px"))
        assertTrue(out.contains("font-size: 14px"))
        listOf("url(", "position", "expression", "behavior", "evil").forEach { assertFalse(out.contains(it, true), it) }
    }

    @Test
    fun colouredButtonsKeepTheirBackgroundAndHiddenTextStaysHidden() {
        val out = clean(
            "<a href=\"https://example.com/verify\" style=\"background:#03c75a;color:#fff;display:inline-block;border-radius:4px\">인증하기</a>" +
                "<div style=\"display:none\">미리보기 문구</div>",
        ).body
        listOf("background: #03c75a", "color: #fff", "display: inline-block", "border-radius: 4px", "display: none").forEach { assertTrue(out.contains(it), it) }
    }

    @Test
    fun contentWrappedInAFormIsKeptWhileItsControlsAreRemoved() {
        val out = clean(
            "<form action=\"https://evil.example/post\"><p>결재 요청이 도착했습니다.</p><input name=\"pw\"><select><option>a</option></select>" +
                "<button type=\"submit\">확인</button></form>",
        ).body
        assertTrue(out.contains("결재 요청이 도착했습니다."))
        assertTrue(out.contains("확인"))
        listOf("<form", "<input", "<button", "<select", "evil.example").forEach { assertFalse(out.contains(it, true), it) }
    }

    @Test
    fun layoutTagsAndKoreanTextSurvive() {
        val out = clean("<table border=1 cellpadding=4><tr><td colspan=2 align=center>안녕하세요</td></tr></table><blockquote>인용</blockquote><center>가운데</center><font color=red>빨강</font>").body
        listOf("<table", "<td", "colspan=\"2\"", "안녕하세요", "<blockquote>", "<center>", "<font").forEach { assertTrue(out.contains(it), it) }
    }

    @Test
    fun malformedMarkupIsHandled() {
        val out = clean("<p><b>unclosed <i>tags <a href=\"https://x.example\">link").body
        assertTrue(out.contains("link"))
        assertEquals("", clean("").body)
    }
}
