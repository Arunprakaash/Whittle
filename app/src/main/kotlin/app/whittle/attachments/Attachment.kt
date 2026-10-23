package app.whittle.attachments

import kotlinx.serialization.Serializable

/** A photo or file the user added to a message, saved in the workspace at [path] (relative to its root). */
@Serializable
data class Attachment(val path: String, val name: String, val sizeBytes: Long, val mimeType: String) {
    val isImage: Boolean get() = mimeType.startsWith(IMAGE_PREFIX)

    private companion object {
        const val IMAGE_PREFIX = "image/"
    }
}

/** How attachments are named and placed; pure, so it's tested on the JVM. */
object AttachmentNames {
    /** The folder a chat's attachments go to: [directory] inside its [project], or at the workspace's top. */
    fun folder(project: String?, directory: String): String = listOfNotNull(project?.trim('/')?.takeIf { it.isNotEmpty() }, directory).joinToString("/")

    /**
     * A name safe for any file system: no folders, no control characters, never empty or hidden. A bare number,
     * which is how Android's photo picker names what it hands over, says nothing, so it takes [fallback] too.
     */
    fun clean(raw: String?, fallback: String): String {
        val name = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() || it in FORBIDDEN }
            .trim().trimStart('.')
        return name.takeUnless { it.isEmpty() || it.substringBeforeLast('.').all(Char::isDigit) } ?: fallback
    }

    /** [wanted], or "name-2.ext", "name-3.ext" and so on: the first that isn't [taken], so nothing is overwritten. */
    fun unique(wanted: String, taken: (String) -> Boolean): String {
        if (!taken(wanted)) return wanted
        val dot = wanted.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { wanted.substring(0, it) } ?: wanted
        val extension = dot?.let { wanted.substring(it) }.orEmpty()
        return generateSequence(FIRST_COPY) { it + 1 }.map { "$stem-$it$extension" }.first { !taken(it) }
    }

    /** "212 KB", "1.8 MB": a size in the units people read. */
    fun size(bytes: Long): String = when {
        bytes < KILO -> "$bytes B"
        bytes < KILO * KILO -> "${(bytes + KILO / 2) / KILO} KB"
        bytes % (KILO * KILO) == 0L -> "${bytes / (KILO * KILO)} MB"
        else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (KILO * KILO).toDouble())
    }

    private const val KILO = 1024L
    private const val FIRST_COPY = 2
    private val FORBIDDEN = setOf('<', '>', ':', '"', '|', '?', '*')
}
