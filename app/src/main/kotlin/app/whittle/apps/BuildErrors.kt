package app.whittle.apps

/** Picks the compiler errors worth sending the agent out of a build's output. */
object BuildErrors {
    // kotlinc: `path:line:col: error: …`; aapt2: `path:line: error: …` or `error: …`.
    private val ERROR = Regex("""(^|[\s:])error:""")

    // d8: `Error: …`, or `Error in path:` with what's wrong on the next line.
    private val D8_ERROR = Regex("""^Error(:| in )""")

    /** The first [maxErrors] error lines of [output] (stderr and stdout together), at most [maxChars] characters; its tail when no line is an error. */
    fun parse(output: String, maxErrors: Int, maxChars: Int): String {
        val lines = output.lines().map { it.trimEnd() }
        val errors = lines.indices.mapNotNull { i ->
            val line = lines[i]
            when {
                ERROR.containsMatchIn(line) -> line
                D8_ERROR.containsMatchIn(line) && line.endsWith(":") -> listOfNotNull(line, lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" ")
                D8_ERROR.containsMatchIn(line) -> line
                else -> null
            }
        }.take(maxErrors)
        val text = if (errors.isEmpty()) output.trim() else errors.joinToString("\n")
        return if (errors.isEmpty()) text.takeLast(maxChars) else text.take(maxChars)
    }
}
