package app.dustbird.mail.ui.message

import java.util.Locale

enum class RequestVerdict { PASS, FONT, BLOCK }

data class PageColors(val background: Int, val text: Int, val link: Int)

data class HtmlPage(val html: String, val background: Int)

object LinkPolicy {
    private val openable = setOf("http", "https", "mailto", "tel")
    private val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    fun schemeOf(url: String): String? = scheme.find(url.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    fun canOpen(url: String): Boolean = url.none { it.isISOControl() || it.isWhitespace() } && schemeOf(url) in openable

    fun differs(shown: String, url: String): Boolean = normalized(shown) != normalized(url)

    fun looksLikeAddress(shown: String): Boolean = shown.none { it.isWhitespace() } && '.' in shown

    private fun normalized(value: String): String = value.trim().lowercase(Locale.ROOT)
        .removePrefix("mailto:").removePrefix("tel:").removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
}

object ResourcePolicy {
    const val FONT_ORIGIN = "https://fonts.dustbird.invalid"
    const val FONT_URL = "$FONT_ORIGIN/pretendard.ttf"

    fun verdict(url: String, isForMainFrame: Boolean, imagesAllowed: Boolean): RequestVerdict {
        if (!isForMainFrame && url == FONT_URL) return RequestVerdict.FONT
        val scheme = LinkPolicy.schemeOf(url)
        val allowed = if (isForMainFrame) scheme == "data" || scheme == "about" else imagesAllowed && scheme == "https"
        return if (allowed) RequestVerdict.PASS else RequestVerdict.BLOCK
    }
}

object SafeHtml {
    val lightPage = PageColors(background = 0xFFFFFFFF.toInt(), text = 0xFF1A1A1A.toInt(), link = 0xFF0B57D0.toInt())

    private val ownColors = Regex("""(?i)<[^>]*(?:\s(?:bgcolor|color)\s*=|[\s"';](?:background-)?color\s*:)""")

    fun contentSecurityPolicy(imagesAllowed: Boolean): String =
        "default-src 'none'; img-src ${if (imagesAllowed) "https: " else ""}data:; style-src 'unsafe-inline'; font-src ${ResourcePolicy.FONT_ORIGIN}"

    fun hasOwnColors(body: String): Boolean = ownColors.containsMatchIn(body)

    fun page(body: String, imagesAllowed: Boolean, themed: PageColors): HtmlPage {
        val colors = if (hasOwnColors(body)) lightPage else themed
        val html = """<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8">""" +
            """<meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(imagesAllowed)}">""" +
            """<meta name="viewport" content="width=device-width, initial-scale=1">""" +
            """<meta name="referrer" content="no-referrer"><style>${css(colors)}</style></head><body>$body</body></html>"""
        return HtmlPage(html, colors.background)
    }

    private fun css(colors: PageColors): String = "@font-face{font-family:Pretendard;src:url(${ResourcePolicy.FONT_URL}) format('truetype');font-weight:100 900;font-display:swap}" +
        "html{background:${hex(colors.background)}}" +
        "body{margin:0;padding:8px 16px 24px;font-family:Pretendard,sans-serif;font-size:16px;line-height:1.55;" +
        "color:${hex(colors.text)};background:${hex(colors.background)};overflow-wrap:anywhere}" +
        "a{color:${hex(colors.link)}}img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}" +
        "blockquote{margin:8px 0 8px 4px;padding-left:12px}"

    private fun hex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
}
