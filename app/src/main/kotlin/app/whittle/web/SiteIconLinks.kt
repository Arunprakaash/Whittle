package app.whittle.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Finds a site's icons in its home page: `<link rel="icon">`, `apple-touch-icon` and similar, best first,
 * then `/favicon.ico`. SVG icons are skipped because Android can't decode them as bitmaps.
 */
object SiteIconLinks {
    fun candidates(pageHtml: String, pageUrl: HttpUrl, preferredPx: Int): List<HttpUrl> {
        val links = LINK_TAG.findAll(pageHtml).mapNotNull { tag -> iconLink(attributes(tag.value), pageUrl) }.toList()
        // Closest at or above the size we draw first, then the largest of the smaller ones.
        val ordered = links.sortedWith(
            compareBy<IconLink> { it.sizePx < preferredPx }.thenBy { if (it.sizePx >= preferredPx) it.sizePx else -it.sizePx },
        )
        return (ordered.map { it.url } + fallback(pageUrl)).distinct()
    }

    /** `https://host/favicon.ico`, the address browsers try when a page names no icon. */
    fun fallback(pageUrl: HttpUrl): HttpUrl = pageUrl.newBuilder().encodedPath(FAVICON_PATH).query(null).fragment(null).build()

    private fun iconLink(attributes: Map<String, String>, pageUrl: HttpUrl): IconLink? {
        val rel = attributes[REL]?.lowercase()?.split(WHITESPACE)?.toSet() ?: return null
        if (rel.none { it in ICON_RELS }) return null
        val href = attributes[HREF]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (attributes[TYPE]?.contains(SVG) == true || href.substringBefore('?').endsWith(SVG_EXTENSION)) return null
        val url = pageUrl.resolve(href)?.takeIf { it.isHttps } ?: return null
        val declared = attributes[SIZES]?.let(::largestSize)
        val size = declared ?: if (rel.any { it.startsWith(APPLE_TOUCH_ICON) }) APPLE_TOUCH_ICON_PX else DEFAULT_ICON_PX
        return IconLink(url, size)
    }

    private fun attributes(tag: String): Map<String, String> = ATTRIBUTE.findAll(tag).associate { match ->
        val (name, doubleQuoted, singleQuoted, bare) = match.destructured
        name.lowercase() to doubleQuoted.ifEmpty { singleQuoted.ifEmpty { bare } }
    }

    private fun largestSize(sizes: String): Int? =
        SIZE.findAll(sizes).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()

    private data class IconLink(val url: HttpUrl, val sizePx: Int)

    private const val REL = "rel"
    private const val HREF = "href"
    private const val TYPE = "type"
    private const val SIZES = "sizes"
    private const val SVG = "svg"
    private const val SVG_EXTENSION = ".svg"
    private const val FAVICON_PATH = "/favicon.ico"
    private const val APPLE_TOUCH_ICON = "apple-touch-icon"
    private const val APPLE_TOUCH_ICON_PX = 180
    private const val DEFAULT_ICON_PX = 32
    private val ICON_RELS = setOf("icon", APPLE_TOUCH_ICON, "apple-touch-icon-precomposed")
    private val WHITESPACE = Regex("""\s+""")
    private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("""([a-zA-Z][\w-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val SIZE = Regex("""(\d+)x\d+""", RegexOption.IGNORE_CASE)
}

/** Only public web hosts get icon requests: no IP addresses, no single-label or local names. */
internal fun publicHomePage(host: String): HttpUrl? {
    val name = host.trim().lowercase().trimEnd('.')
    if (!name.contains('.') || name.endsWith(LOCAL_SUFFIX) || IP_V4.matches(name) || name.contains(':')) return null
    return "https://$name/".toHttpUrlOrNull()?.takeIf { it.host == name }
}

private const val LOCAL_SUFFIX = ".local"
private val IP_V4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
