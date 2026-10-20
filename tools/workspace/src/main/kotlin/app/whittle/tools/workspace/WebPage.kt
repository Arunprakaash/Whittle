package app.whittle.tools.workspace

/**
 * A web app's HTML page around a build: which local scripts it loads (the entries to bundle), and the same
 * page rewritten to load the bundles from the output folder. [location] is the page's path in the workspace.
 */
class WebPage(private val html: String, private val location: String) {
    /** Workspace paths of the local scripts the page loads whose extension is in [extensions], in page order. */
    fun scripts(extensions: List<String>): List<String> = SCRIPT.findAll(html)
        .mapNotNull { attribute(it.value, SRC) }
        .mapNotNull { workspacePath(it) }
        .filter { path -> extensions.any { path.lowercase().endsWith(it) } }
        .distinct()
        .toList()

    /**
     * The page as it will sit in the output folder: each bundled script tag loads its bundle (as a module
     * when [modules]), stylesheets the bundles produced are linked in the head, and every other local link
     * points at the workspace root so it still resolves from the new folder.
     */
    fun built(bundles: Map<String, Bundle>, modules: Boolean): String {
        var page = LOCAL_REFERENCE.replace(html) { match ->
            val (prefix, quote, value) = match.destructured
            val path = workspacePath(value)
            if (path == null || value.startsWith("/")) match.value else "$prefix$quote/$path$quote"
        }
        page = SCRIPT.replace(page) { match ->
            val tag = match.value
            val bundle = attribute(tag, SRC)?.let(::workspacePath)?.let(bundles::get) ?: return@replace tag
            var opening = tag.substringBefore('>') + ">"
            opening = SRC_ATTRIBUTE.replace(opening) { "${it.groupValues[1]}\"./${bundle.script}\"" }
            if (modules && attribute(opening, TYPE)?.lowercase() != MODULE) {
                opening = TYPE_ATTRIBUTE.replace(opening, "").replaceFirst(SCRIPT_OPEN, "<script type=\"$MODULE\"")
            }
            opening + tag.substringAfter('>')
        }
        val stylesheets = bundles.values.mapNotNull { it.stylesheet }.distinct()
            .joinToString("") { "<link rel=\"stylesheet\" href=\"./$it\">" }
        if (stylesheets.isEmpty()) return page
        HEAD_END.find(page)?.let { return page.replaceRange(it.range.first, it.range.first, stylesheets) }
        return stylesheets + page
    }

    /** Where a local reference lands in the workspace, or null for web links, data, anchors and anything escaping the root. */
    private fun workspacePath(reference: String): String? {
        val value = reference.substringBefore('?').substringBefore('#').trim()
        if (value.isEmpty() || REMOTE.containsMatchIn(value)) return null
        val parts = if (value.startsWith("/")) mutableListOf() else location.split('/').dropLast(1).toMutableList()
        for (part in value.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }

    private fun attribute(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    /** What one entry script became, as file names in the output folder. */
    data class Bundle(val script: String, val stylesheet: String?)

    private companion object {
        const val SRC = "src"
        const val TYPE = "type"
        const val MODULE = "module"
        val REMOTE = Regex("""^([a-z][a-z0-9+.-]*:|//)""", RegexOption.IGNORE_CASE)
        val SCRIPT = Regex("""<script\b[^>]*>[\s\S]*?</script\s*>""", RegexOption.IGNORE_CASE)
        val SCRIPT_OPEN = Regex("""^<script""", RegexOption.IGNORE_CASE)
        val SRC_ATTRIBUTE = Regex("""(\bsrc\s*=\s*)["'][^"']*["']""", RegexOption.IGNORE_CASE)
        val TYPE_ATTRIBUTE = Regex("""\s+type\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE)
        val LOCAL_REFERENCE = Regex("""(\b(?:src|href)\s*=\s*)(["'])([^"']*)\2""", RegexOption.IGNORE_CASE)
        val HEAD_END = Regex("""</head\s*>""", RegexOption.IGNORE_CASE)
    }
}
