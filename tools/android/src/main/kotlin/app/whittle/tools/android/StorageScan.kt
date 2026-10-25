package app.whittle.tools.android

import java.io.File
import java.nio.file.Files

/** One child of the folder that was scanned. [partial] means the scan ran out of time, so [bytes] is at least this much. */
data class FolderSize(val name: String, val bytes: Long, val isDirectory: Boolean, val partial: Boolean)

/**
 * The biggest [folders] of a scan. [partial] is true when any child was cut short, including those that didn't make
 * the list: a folder never reached sorts last, so the list may then be missing some of the biggest.
 */
data class FolderSizes(val folders: List<FolderSize>, val partial: Boolean)

/**
 * The sizes of [root]'s children, largest first, at most [limit]. Summing stops when [now] reaches [deadlineMillis]
 * (a clock reading, not a duration), and whatever was cut short is marked partial. Symlinks are not followed and
 * unreadable folders count as empty, so one odd folder can't fail or loop the scan.
 */
fun topFolders(root: File, limit: Int, deadlineMillis: Long, now: () -> Long): FolderSizes {
    val children = root.listFiles().orEmpty().map { child ->
        if (Files.isSymbolicLink(child.toPath())) FolderSize(child.name, 0, false, false)
        else {
            val sum = Sum()
            sum.add(child, deadlineMillis, now)
            FolderSize(child.name, sum.bytes, child.isDirectory, sum.partial)
        }
    }
    return FolderSizes(children.sortedByDescending { it.bytes }.take(limit), children.any { it.partial })
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

/** A size that may be a floor: [partial] means the scan ran out of time. */
data class Measured(val bytes: Long, val partial: Boolean)

/**
 * Bytes in the files under [dir] last changed before [cutoffMillis]; symlinks skipped. Stops at [deadlineMillis]
 * (a clock reading from [now]) and then reports [Measured.partial].
 */
fun bytesOlderThan(dir: File, cutoffMillis: Long, deadlineMillis: Long, now: () -> Long): Measured {
    var bytes = 0L
    var partial = false
    fun walk(folder: File) {
        for (file in folder.listFiles().orEmpty()) {
            if (partial) return
            if (now() >= deadlineMillis) {
                partial = true
                return
            }
            when {
                Files.isSymbolicLink(file.toPath()) -> Unit
                file.isDirectory -> walk(file)
                file.lastModified() < cutoffMillis -> bytes += file.length()
            }
        }
    }
    walk(dir)
    return Measured(bytes, partial)
}
