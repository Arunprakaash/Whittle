package app.whittle.files

import kotlinx.serialization.Serializable

/** A file a chat's agent wrote, with what it held before the chat first touched it. */
@Serializable
data class FileChange(
    /** Relative to the workspace. */
    val path: String,
    /** The chat made it; there was nothing before. */
    val created: Boolean,
    /** What it held before the chat's first write; null when it was new, binary or too large to keep. */
    val baseline: String?,
    val lastAtMillis: Long,
    val edits: Int,
) {
    /** Whether Changes can show this file line by line. */
    val comparable: Boolean get() = created || baseline != null

    companion object {
        /** The ones of [changes] inside [folder] (relative to the workspace), or all of them at the workspace itself. */
        fun within(folder: String, changes: List<FileChange>): List<FileChange> {
            val prefix = folder.trim('/').takeIf { it.isNotEmpty() }?.let { "$it/" } ?: return changes
            return changes.filter { it.path.startsWith(prefix) }
        }
    }
}
