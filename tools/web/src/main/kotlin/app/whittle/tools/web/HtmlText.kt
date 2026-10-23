package app.whittle.tools.web

import okhttp3.HttpUrl

/**
 * A web page as a reader sees it: its title, then its text, with no scripts, styles or markup. Links keep their
 * address after their words, so the model can follow them; blocks such as paragraphs and list items keep their
 * own lines.
 */
internal object HtmlText {
    fun of(html: String, page: HttpUrl?): String {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { decode(stripTags(it)).trim() }.orEmpty()
        val visible = html
            .replace(COMMENT, " ")
            .replace(HIDDEN) { " " }
            .replace(CHROME) { " " }
        // Where a page marks its main content, that is what the model wants; menus and sidebars only cost room.
        var text = MAIN.find(visible)?.value ?: ARTICLE.findAll(visible).map { it.value }.toList().ifEmpty { listOf(visible) }.joinToString("\n")
        text = LINK.replace(text) { match -> link(match.groupValues[1], match.groupValues[2], page) }
        text = text
            .replace(LINE_BREAK, "\n")
            .replace(LIST_ITEM, "\n- ")
            .replace(BLOCK, "\n")
            .replace(CELL, " ")
        text = decode(stripTags(text))
        val lines = text.lines()
            .map { it.replace(SPACES, " ").trim() }
            .fold(mutableListOf<String>()) { kept, line ->
                // Runs of empty lines become one.
                if (line.isNotEmpty() || kept.lastOrNull()?.isNotEmpty() == true) kept += line
                kept
            }
        val body = lines.joinToString("\n").trim()
        // The page's own address heads the text, so links within the site can be given as just their path.
        val heading = listOfNotNull(title.takeIf { it.isNotEmpty() && !body.startsWith(it) }, page?.toString()).joinToString("\n")
        return if (heading.isEmpty()) body else "$heading\n\n$body"
    }

    private fun link(href: String, inner: String, page: HttpUrl?): String {
        val words = decode(stripTags(inner)).replace(SPACES, " ").trim()
        val address = decode(href).let { page?.resolve(it) }?.takeIf { it.scheme == "http" || it.scheme == "https" }
        val shown = address?.let { if (it.host == page?.host) it.encodedPath + (it.encodedQuery?.let { query -> "?$query" } ?: "") else it.toString() }
        return when {
            words.isEmpty() -> " "
            shown == null -> " $words "
            else -> " $words ($shown) "
        }
    }

    /** Inline tags (bold, spans) sit inside words and sentences, so they go without leaving a space. */
    private fun stripTags(html: String): String = html.replace(TAG, "")

    private fun decode(text: String): String = ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(HEX)?.let(::character)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::character)
            else -> NAMED[name.lowercase()]
        } ?: match.value
    }

    private fun character(code: Int): String? = runCatching { String(Character.toChars(code)) }.getOrNull()

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", OPTIONS)
    private val COMMENT = Regex("<!--.*?-->", OPTIONS)
    private val HIDDEN = Regex("<(head|script|style|noscript|svg|template|iframe|canvas)\\b[^>]*>.*?</\\1\\s*>", OPTIONS)
    private val CHROME = Regex("<(nav|header|footer|aside|form)\\b[^>]*>.*?</\\1\\s*>", OPTIONS)
    private val MAIN = Regex("<main\\b[^>]*>.*?</main\\s*>", OPTIONS)
    private val ARTICLE = Regex("<article\\b[^>]*>.*?</article\\s*>", OPTIONS)
    private val LINK = Regex("<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a\\s*>", OPTIONS)
    private val LINE_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val LIST_ITEM = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BLOCK = Regex(
        "</?(p|div|section|article|main|header|footer|nav|aside|ul|ol|dl|dt|dd|table|tr|h[1-6]|blockquote|pre|figure|figcaption|form)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val CELL = Regex("</?(td|th)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-f]+|#[0-9]+|[a-z]+);", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private const val HEX = 16
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "copy" to "©", "reg" to "®", "middot" to "·", "bull" to "•",
    )
}
