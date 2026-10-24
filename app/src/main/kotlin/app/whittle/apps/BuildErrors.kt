package app.whittle.apps

/** Picks the compiler errors worth sending the agent out of a build's output. */
object BuildErrors {
    // kotlinc: `path:line:col: error: …`; aapt2: `path:line: error: …` or `error: …`.
    private val ERROR = Regex("""(^|[\s:])error:""")

    /** The first [maxErrors] error lines of [output] (stderr and stdout together), at most [maxChars] characters; its tail when no line is an error. */
    fun parse(output: String, maxErrors: Int, maxChars: Int): String {
        val errors = output.lineSequence().map { it.trimEnd() }.filter { ERROR.containsMatchIn(it) }.take(maxErrors).toList()
        val text = if (errors.isEmpty()) output.trim() else errors.joinToString("\n")
        return if (errors.isEmpty()) text.takeLast(maxChars) else text.take(maxChars)
    }
}
