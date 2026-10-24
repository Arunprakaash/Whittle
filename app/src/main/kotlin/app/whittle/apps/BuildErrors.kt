package app.whittle.apps

/** Picks the compiler errors worth sending the agent out of a build's output. */
object BuildErrors {
    // kotlinc: `path:line:col: error: …`; aapt2: `path:line: error: …` or `error: …`.
    private val ERROR = Regex("""(^|[\s:])error:""")

    // d8: `Error: …`, or `Error in path:` with what's wrong on the next line.
    private val D8_ERROR = Regex("""^Error(:| in )""")
    private const val D8_IN = "Error in "

    /** The first [maxErrors] error lines of [output] (stderr and stdout together), at most [maxChars] characters; its tail when no line is an error. */
    fun parse(output: String, maxErrors: Int, maxChars: Int): String {
        val lines = output.lines().map { it.trimEnd() }
        val errors = mutableListOf<String>()
        var i = 0
        while (i < lines.size && errors.size < maxErrors) {
            val line = lines[i++]
            val detail = lines.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            when {
                ERROR.containsMatchIn(line) -> errors += line
                line.startsWith(D8_IN) && line.endsWith(":") && detail != null -> {
                    errors += "$line $detail"
                    i++ // the detail line is part of this error, not another
                }
                D8_ERROR.containsMatchIn(line) -> errors += line
            }
        }
        val text = if (errors.isEmpty()) output.trim() else errors.joinToString("\n")
        return if (errors.isEmpty()) text.takeLast(maxChars) else text.take(maxChars)
    }
}
