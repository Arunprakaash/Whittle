package app.whittle.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Gentle colour for any text file: keywords, quoted strings and line comments. It reads one line at a time, so a
 * string or comment spanning lines is only coloured where it starts; that's fine for reading.
 */
class CodeColors(
    /** False for prose: it shows as it is. */
    private val code: Boolean,
    private val keywords: Set<String>,
    /** The line comment marker of this file's language, if it has one. */
    private val comment: String?,
    private val keyword: Color,
    private val string: Color,
    private val note: Color,
) {
    fun line(text: String): AnnotatedString = if (code) colored(text) else AnnotatedString(text)

    private fun colored(text: String): AnnotatedString = buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                comment != null && text.startsWith(comment, index) -> {
                    withStyle(SpanStyle(color = note)) { append(text.substring(index)) }
                    index = text.length
                }
                char in QUOTES -> {
                    val end = closing(text, index)
                    withStyle(SpanStyle(color = string)) { append(text.substring(index, end)) }
                    index = end
                }
                char.isLetter() || char == '_' -> {
                    var end = index
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
                    val word = text.substring(index, end)
                    if (word in keywords) withStyle(SpanStyle(color = keyword)) { append(word) } else append(word)
                    index = end
                }
                else -> {
                    append(char)
                    index++
                }
            }
        }
    }

    /** Just past the quote that closes the one at [start], or the end of the line. */
    private fun closing(text: String, start: Int): Int {
        val quote = text[start]
        var index = start + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                quote -> return index + 1
                else -> index++
            }
        }
        return text.length
    }

    private companion object {
        val QUOTES = setOf('"', '\'', '`')
    }
}
