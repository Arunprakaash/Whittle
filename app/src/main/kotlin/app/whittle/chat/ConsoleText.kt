package app.whittle.chat

/**
 * A console message as plain text. WebView hands over `console.log("%cHi", "color: red")` as the format string with
 * its arguments after it ("%cHi color: red"), so the `%c` markers go, and so does the CSS each one was given, which
 * comes last. Only a run of `property: value` declarations at the very end counts as that CSS.
 */
internal fun plainConsoleText(message: String): String {
    val styles = STYLE_MARKER.findAll(message).count()
    if (styles == 0) return message
    val text = message.replace(STYLE_MARKER, "")
    val styling = Regex("""(?:\s+$DECLARATIONS){1,$styles}\s*$""")
    return text.replace(styling, "")
}

private val STYLE_MARKER = Regex("%c")
private const val DECLARATION = """[a-z-]+\s*:\s*[^;:]+"""
private const val DECLARATIONS = """$DECLARATION(?:;\s*$DECLARATION)*;?"""
