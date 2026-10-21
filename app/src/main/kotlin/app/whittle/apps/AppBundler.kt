package app.whittle.apps

import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * Turns a multi-file web app into one self-contained HTML file, so a browser or another app can open it
 * without access to Whittle's files: local stylesheets, scripts and images are inlined; web links stay.
 * Local ES module imports inside scripts aren't followed, so apps split into modules keep only their entry.
 */
class AppBundler(private val maxInlineBytes: Long) {
    fun bundle(root: File, entry: File): String {
        val base = entry.parentFile ?: root
        var html = entry.readText()
        html = STYLESHEET.replace(html) { link ->
            val href = attribute(link.value, HREF) ?: return@replace link.value
            local(root, base, href)?.let { "<style>\n${it.readText()}\n</style>" } ?: link.value
        }
        html = SCRIPT.replace(html) { script ->
            val (before, src, after) = script.destructured
            val file = local(root, base, src) ?: return@replace script.value
            "<script$before$after>\n${file.readText().replace(SCRIPT_END, ESCAPED_SCRIPT_END)}\n</script>"
        }
        html = IMAGE.replace(html) { image ->
            val (prefix, src, suffix) = image.destructured
            val file = local(root, base, src) ?: return@replace image.value
            val type = MEDIA_TYPES[file.extension.lowercase()] ?: return@replace image.value
            "$prefix${dataUri(type, file)}$suffix"
        }
        return html
    }

    /** A file the page links to, if it is local, inside the workspace, and small enough to inline. */
    private fun local(root: File, base: File, reference: String): File? {
        val path = reference.substringBefore('?').substringBefore('#').trim()
        if (path.isEmpty() || REMOTE.containsMatchIn(path)) return null
        return try {
            val workspace = root.canonicalFile
            val file = (if (path.startsWith("/")) File(workspace, path.removePrefix("/")) else File(base, path)).canonicalFile
            file.takeIf { it.isFile && it.path.startsWith(workspace.path + File.separator) && it.length() <= maxInlineBytes }
        } catch (exception: IOException) {
            null
        }
    }

    private fun dataUri(type: String, file: File): String = "data:$type;base64," + Base64.getEncoder().encodeToString(file.readBytes())

    private fun attribute(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    private companion object {
        const val HREF = "href"
        const val SCRIPT_END = "</script"
        const val ESCAPED_SCRIPT_END = "<\\/script"
        val REMOTE = Regex("""^([a-z][a-z0-9+.-]*:|//)""", RegexOption.IGNORE_CASE)
        val STYLESHEET = Regex("""<link\b[^>]*\brel\s*=\s*["']?stylesheet["']?[^>]*>""", RegexOption.IGNORE_CASE)
        val SCRIPT = Regex("""<script\b([^>]*?)\s*\bsrc\s*=\s*["']([^"']+)["']([^>]*)>\s*</script>""", RegexOption.IGNORE_CASE)
        val IMAGE = Regex("""(<img\b[^>]*?\bsrc\s*=\s*["'])([^"']+)(["'])""", RegexOption.IGNORE_CASE)
        val MEDIA_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
        )
    }
}
