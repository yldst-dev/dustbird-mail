package app.dustbird.mail.ui.message

object PlainLinks {
    data class Span(val start: Int, val end: Int, val url: String)

    private const val LIMIT = 300
    private const val URL_CHARS = """A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%"""
    private val pattern = Regex(
        """(?<![A-Za-z0-9@])(https?://[$URL_CHARS]+|www\.[$URL_CHARS]+)|(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""",
    )
    private val trailing = setOf('.', ',', ';', ':', '!', '?', '\'', '"')

    fun find(text: String): List<Span> = pattern.findAll(text).take(LIMIT).mapNotNull { match ->
        val web = match.groups[1]
        if (web == null) {
            val mail = match.groups[2]!!.value.trimEnd('.')
            return@mapNotNull Span(match.range.first, match.range.first + mail.length, "mailto:$mail")
        }
        val shown = trimmed(web.value)
        if (shown.substringAfter("://").isEmpty() || shown == "www.") return@mapNotNull null
        Span(match.range.first, match.range.first + shown.length, if (shown.startsWith("www.", ignoreCase = true)) "https://$shown" else shown)
    }.toList()

    private fun trimmed(candidate: String): String {
        var end = candidate.length
        while (end > 0) {
            val last = candidate[end - 1]
            val unmatched = (last == ')' && candidate.substring(0, end).count { it == '(' } < candidate.substring(0, end).count { it == ')' }) ||
                (last == ']' && candidate.substring(0, end).count { it == '[' } < candidate.substring(0, end).count { it == ']' })
            if (last in trailing || unmatched) end-- else break
        }
        return candidate.substring(0, end)
    }
}
