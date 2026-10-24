package app.whittle.chat

import app.whittle.tools.workspace.ChatProjects

/** Line breaks, control and format characters (such as bidi overrides), and runs of whitespace. */
private val BREAKS = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\s]+")

private const val ELLIPSIS = "…"

/**
 * A name from the workspace (an app's from whittle-app.json, a process's) as it may appear in an Ask to fix request, which
 * reads as the user's own words: one line, at most [maxChars] long, cut with an ellipsis.
 */
fun requestName(name: String, maxChars: Int): String {
    val line = name.replace(BREAKS, " ").trim()
    return if (line.length <= maxChars) line else line.take(maxChars - ELLIPSIS.length).trimEnd() + ELLIPSIS
}

/**
 * The card's project folder, for its Ask to fix request: only a plain folder name whose package is Whittle's [prefix] plus that
 * name, the pairing build_app checks before it builds. Null otherwise, and then the card offers no Ask to fix.
 */
fun fixProject(card: ThreadItem.App, prefix: String): String? =
    projectOf(card).takeIf { ChatProjects.isValidName(it) && card.packageName == prefix + it }

/**
 * Whether Ask to fix can send now. While a file is still importing, or with no model chosen, sending waits, and the pasted output
 * would be lost, so the button is off until then.
 */
fun canAskToFix(attaching: Int, hasModel: Boolean): Boolean = attaching == 0 && hasModel
