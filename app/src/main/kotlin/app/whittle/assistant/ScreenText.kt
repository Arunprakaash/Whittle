package app.whittle.assistant

/** One view on the screen as the assist structure gives it: its text, whether it's a password field, and what it holds. */
data class ScreenNode(val text: String?, val password: Boolean, val children: List<ScreenNode> = emptyList())

/**
 * The words on the screen, one line per piece of text, in reading order: top to bottom as the views nest. Password
 * fields are left out, repeats in a row (a label and its content description, say) are kept once, and the result is
 * cut to [maxChars].
 */
fun screenText(roots: List<ScreenNode>, maxChars: Int): String {
    val lines = mutableListOf<String>()
    fun visit(node: ScreenNode) {
        if (!node.password) {
            node.text?.trim()?.takeIf { it.isNotEmpty() && it != lines.lastOrNull() }?.let(lines::add)
        }
        node.children.forEach(::visit)
    }
    roots.forEach(::visit)
    return lines.joinToString("\n").take(maxChars)
}
