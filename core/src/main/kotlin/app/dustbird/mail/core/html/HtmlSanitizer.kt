package app.dustbird.mail.core.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

data class SanitizedHtml(val body: String, val blockedRemoteImages: Int)

object HtmlSanitizer {
    private val dropped = "script, style, iframe, frame, frameset, object, embed, applet, form, input, button, textarea, select, " +
        "option, link, meta, base, svg, math, audio, video, source, track, canvas, noscript, template, dialog, head, title"
    private val allowedStyleProperties = setOf(
        "color", "background-color", "font-family", "font-size", "font-style", "font-weight", "text-align", "text-decoration",
        "line-height", "margin", "margin-top", "margin-bottom", "margin-left", "margin-right", "padding", "padding-top",
        "padding-bottom", "padding-left", "padding-right", "border", "border-top", "border-bottom", "border-left",
        "border-right", "border-color", "border-width", "border-style", "width", "height", "max-width", "vertical-align",
        "white-space", "text-indent", "list-style-type",
    )

    fun sanitize(html: String, allowRemoteImages: Boolean): SanitizedHtml {
        val source = Jsoup.parse(html)
        source.select(dropped).remove()
        var blocked = 0
        source.select("img").forEach { image ->
            val src = image.attr("src").trim()
            if (allowRemoteImages && src.startsWith("https://", ignoreCase = true)) return@forEach
            if (src.isNotEmpty()) blocked++
            image.removeAttr("src")
        }
        val cleaned = Cleaner(safelist()).clean(source)
        cleaned.select("img:not([src])").forEach { image ->
            val alt = image.attr("alt")
            if (alt.isBlank()) image.remove() else image.replaceWith(org.jsoup.nodes.TextNode(alt))
        }
        cleaned.allElements.forEach(::filterStyle)
        return SanitizedHtml(cleaned.body().html(), blocked)
    }

    private fun safelist(): Safelist = Safelist.relaxed()
        .addTags("center", "font", "hr", "small")
        .addAttributes(":all", "style", "dir", "align", "bgcolor")
        .addAttributes("font", "color", "face", "size")
        .addAttributes("table", "border", "cellpadding", "cellspacing", "width")
        .addAttributes("td", "colspan", "rowspan", "valign", "width", "height")
        .addAttributes("th", "colspan", "rowspan", "valign", "width", "height")
        .addProtocols("a", "href", "http", "https", "mailto", "tel")
        .removeProtocols("img", "src", "http")
        .preserveRelativeLinks(false)
        .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")

    private fun filterStyle(element: Element) {
        if (!element.hasAttr("style")) return
        val kept = element.attr("style").split(';').mapNotNull { declaration ->
            val name = declaration.substringBefore(':').trim().lowercase()
            val value = declaration.substringAfter(':', "").trim()
            val safe = name in allowedStyleProperties && value.isNotEmpty() && value.none { it == '\\' || it == '<' } &&
                listOf("url(", "expression", "javascript:", "@import", "behavior").none { it in value.lowercase() }
            if (safe) "$name: $value" else null
        }
        if (kept.isEmpty()) element.removeAttr("style") else element.attr("style", kept.joinToString("; "))
    }
}
