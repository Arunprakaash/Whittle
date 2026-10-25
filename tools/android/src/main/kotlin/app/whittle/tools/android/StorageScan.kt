package app.whittle.tools.android

import java.io.File
import java.nio.file.Files

/** One child of the folder that was scanned. [partial] means the scan ran out of time, so [bytes] is at least this much. */
data class FolderSize(val name: String, val bytes: Long, val isDirectory: Boolean, val partial: Boolean)

/**
 * The sizes of [root]'s children, largest first, at most [limit]. Summing stops when [now] reaches [deadlineMillis]
 * (a clock reading, not a duration), and whatever was cut short is marked partial. Symlinks are not followed and
 * unreadable folders count as empty, so one odd folder can't fail or loop the scan.
 */
fun topFolders(root: File, limit: Int, deadlineMillis: Long, now: () -> Long): List<FolderSize> {
    val children = root.listFiles().orEmpty()
    return children
        .map { child ->
            val sum = Sum()
            if (Files.isSymbolicLink(child.toPath())) FolderSize(child.name, 0, false, false)
            else {
                sum.add(child, deadlineMillis, now)
                FolderSize(child.name, sum.bytes, child.isDirectory, sum.partial)
            }
        }
        .sortedByDescending { it.bytes }
        .take(limit)
}

private class Sum {
    var bytes = 0L
    var partial = false

    fun add(file: File, deadlineMillis: Long, now: () -> Long) {
        if (partial) return
        if (now() >= deadlineMillis) {
            partial = true
            return
        }
        if (Files.isSymbolicLink(file.toPath())) return
        if (!file.isDirectory) {
            bytes += file.length()
            return
        }
        file.listFiles()?.forEach { add(it, deadlineMillis, now) }
    }
}

/** Bytes in the files directly or deeply under [dir] last changed before [cutoffMillis]; symlinks skipped. */
fun bytesOlderThan(dir: File, cutoffMillis: Long): Long =
    dir.listFiles().orEmpty().sumOf { file ->
        when {
            Files.isSymbolicLink(file.toPath()) -> 0L
            file.isDirectory -> bytesOlderThan(file, cutoffMillis)
            file.lastModified() < cutoffMillis -> file.length()
            else -> 0L
        }
    }
