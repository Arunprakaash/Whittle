package app.whittle.design.components

/** A piece of model prose: running text, or a table. */
sealed interface MarkdownBlock {
    data class Text(val text: String) : MarkdownBlock

    /** [header] and each row have the same number of cells; missing cells are empty. */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
}

/**
 * Splits prose into text and GitHub-style tables (a header row, a |---| separator, then rows). Lines
 * that only look like a table, or a table still streaming in before its separator arrives, stay text.
 */
object MarkdownBlocks {
    fun parse(text: String): List<MarkdownBlock> {
        val lines = withoutCitationMarkers(text).split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        val pending = StringBuilder()
        fun flushText() {
            // Blocks are spaced apart on screen, so blank lines at a text block's edges aren't kept.
            pending.toString().trim('\n').takeIf { it.isNotBlank() }?.let { blocks += MarkdownBlock.Text(it) }
            pending.setLength(0)
        }
        var index = 0
        while (index < lines.size) {
            val header = lines[index]
            val separator = lines.getOrNull(index + 1)
            if (isRow(header) && separator != null && SEPARATOR.matches(separator.trim())) {
                val columns = cells(header)
                var end = index + 2
                val rows = mutableListOf<List<String>>()
                while (end < lines.size && isRow(lines[end])) {
                    rows += cells(lines[end]).let { row -> List(columns.size) { row.getOrElse(it) { "" } } }
                    end++
                }
                flushText()
                blocks += MarkdownBlock.Table(columns, rows)
                index = end
                continue
            }
            pending.append(header).append('\n')
            index++
        }
        flushText()
        return blocks
    }

    /**
     * A text block's paragraphs, split at blank lines but never inside a ``` fence, so a long streaming answer can be
     * laid out a paragraph at a time: only the last one changes as text arrives.
     */
    fun paragraphs(text: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = mutableListOf<String>()
        var inFence = false
        fun flush() {
            if (current.isNotEmpty()) paragraphs += current.joinToString("\n")
            current.clear()
        }
        for (line in text.split('\n')) {
            if (FENCE.matches(line)) inFence = !inFence
            if (line.isBlank() && !inFence) flush() else current += line
        }
        flush()
        return paragraphs
    }

    /**
     * A paragraph in runs of up to [maxLines] lines, so a long list streaming in only lays out its last run again.
     * A paragraph with a ``` fence stays whole: the fence styles the lines after it.
     */
    fun runs(paragraph: String, maxLines: Int): List<String> {
        val lines = paragraph.split('\n')
        if (lines.size <= maxLines || lines.any(FENCE::matches)) return listOf(paragraph)
        return lines.chunked(maxLines) { it.joinToString("\n") }
    }

    private fun isRow(line: String): Boolean = line.trim().let { it.startsWith(PIPE) && it.count { c -> c == '|' } >= 2 }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix(PIPE).removeSuffix(PIPE).split(CELL_SPLIT).map { it.trim().replace(ESCAPED_PIPE, PIPE) }

    private const val PIPE = "|"
    private const val ESCAPED_PIPE = "\\|"

    /** A pipe that isn't escaped with a backslash. */
    private val CELL_SPLIT = Regex("""(?<!\\)\|""")
    /** A ``` line, which opens or closes a code block (as in [lightMarkdown]). */
    private val FENCE = Regex("""^\s*```.*$""")
    private val SEPARATOR = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")
}
