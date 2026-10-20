package app.whittle.design.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The light formatting models use in prose: **bold**, *italic*, `code`, bullet and numbered lists,
 * headings (shown as bold lines) and code fences (shown in [code] style without the fences).
 * Anything else, including an unclosed marker mid-stream, stays literal text.
 */
fun lightMarkdown(text: String, code: SpanStyle): AnnotatedString = buildAnnotatedString {
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
            withStyle(code) { append(line) }
            continue
        }
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        val heading = HEADING.matchEntire(line)
        when {
            bullet != null -> {
                append(bullet.groupValues[1])
                append(BULLET_MARK)
                appendInline(bullet.groupValues[2], code)
            }
            numbered != null -> {
                append(numbered.groupValues[1])
                append(numbered.groupValues[2])
                append(NUMBER_SEPARATOR)
                appendInline(numbered.groupValues[3], code)
            }
            heading != null -> withStyle(EMPHASIS) { appendInline(heading.groupValues[1], code) }
            else -> appendInline(line, code)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String, code: SpanStyle) {
    var index = 0
    while (index < text.length) {
        val span = InlineSpan.entries.firstOrNull { text.startsWith(it.marker, index) }
        val contentStart = index + (span?.marker?.length ?: 0)
        val end = span?.let { text.indexOf(it.marker, contentStart) } ?: -1
        val content = if (span != null && end > contentStart) text.substring(contentStart, end) else null
        if (span != null && content != null && span.accepts(content)) {
            when (span) {
                InlineSpan.Code -> withStyle(code) { append(content) }
                InlineSpan.Bold, InlineSpan.BoldUnderscore -> withStyle(EMPHASIS) { appendInline(content, code) }
                InlineSpan.Italic -> withStyle(ITALIC) { appendInline(content, code) }
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
