package app.whittle.design.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * The light formatting models use in prose: **bold**, *italic*, `code`, [links](https://…) (tappable,
 * in [link] style; web search answers cite their sources this way), bullet and numbered lists,
 * headings (shown as bold lines) and code fences (shown in [code] style without the fences).
 * Anything else, including an unclosed marker mid-stream, stays literal text.
 */
fun lightMarkdown(text: String, code: SpanStyle, link: SpanStyle = SpanStyle()): AnnotatedString = buildAnnotatedString {
    val inline = InlineStyles(code, link)
    var inFence = false
    var firstLine = true
    for (line in text.split('\n')) {
        if (FENCE.matches(line)) {
            inFence = !inFence
            continue
        }
        if (!firstLine) append('\n')
        firstLine = false
        if (inFence) {
            withStyle(inline.code) { append(line) }
            continue
        }
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        val heading = HEADING.matchEntire(line)
        when {
            bullet != null -> {
                append(bullet.groupValues[1])
                append(BULLET_MARK)
                appendInline(bullet.groupValues[2], inline)
            }
            numbered != null -> {
                append(numbered.groupValues[1])
                append(numbered.groupValues[2])
                append(NUMBER_SEPARATOR)
                appendInline(numbered.groupValues[3], inline)
            }
            heading != null -> withStyle(EMPHASIS) { appendInline(heading.groupValues[1], inline) }
            else -> appendInline(line, inline)
        }
    }
}

/** A `[label](https://…)` link in model prose. */
data class MarkdownLink(val label: String, val url: String)

/** The web links in [text], in order, as [lightMarkdown] makes them tappable. */
fun markdownLinks(text: String): List<MarkdownLink> =
    LINK.findAll(text).map { MarkdownLink(label = it.groupValues[1], url = it.groupValues[2]) }.toList()

private class InlineStyles(val code: SpanStyle, val link: SpanStyle)

private fun AnnotatedString.Builder.appendInline(text: String, styles: InlineStyles) {
    var index = 0
    while (index < text.length) {
        val link = LINK.matchAt(text, index)
        if (link != null) {
            val (label, url) = link.destructured
            withLink(LinkAnnotation.Url(url, TextLinkStyles(styles.link))) { appendInline(label, styles) }
            index = link.range.last + 1
            continue
        }
        val span = InlineSpan.entries.firstOrNull { text.startsWith(it.marker, index) }
        val contentStart = index + (span?.marker?.length ?: 0)
        val end = span?.let { text.indexOf(it.marker, contentStart) } ?: -1
        val content = if (span != null && end > contentStart) text.substring(contentStart, end) else null
        if (span != null && content != null && span.accepts(content)) {
            when (span) {
                InlineSpan.Code -> withStyle(styles.code) { append(content) }
                InlineSpan.Bold, InlineSpan.BoldUnderscore -> withStyle(EMPHASIS) { appendInline(content, styles) }
                InlineSpan.Italic -> withStyle(ITALIC) { appendInline(content, styles) }
            }
            index = end + span.marker.length
        } else {
            append(text[index])
            index++
        }
    }
}

/** Ordered so "**" wins over "*". Emphasis must hug its text, so "2 * 3 * 4" stays literal. */
private enum class InlineSpan(val marker: String) {
    Bold("**"),
    BoldUnderscore("__"),
    Code("`"),
    Italic("*"),
    ;

    fun accepts(content: String): Boolean = this == Code || (content.first().isWhitespace().not() && content.last().isWhitespace().not())
}

/** The type scale's emphasis weight; the bundled fonts carry no heavier instance. */
private val EMPHASIS = SpanStyle(fontWeight = FontWeight(600))
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private const val BULLET_MARK = "•  "
private const val NUMBER_SEPARATOR = ". "
private val FENCE = Regex("""^\s*```.*$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val HEADING = Regex("""^#{1,6}\s+(.*)$""")

/** Only web links open; any other scheme stays literal text. */
private val LINK = Regex("""\[([^\]\n]+)\]\((https?://[^)\s]+)\)""")
