package app.whittle.tools.workspace

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The Linux that commands run in, as the file tools see it. Linux is a folder on the device ([root]), so files are
 * read and written there directly, at the same bytes a command sees. A path resolves the way Linux would resolve it,
 * links included, with the chat's own folder mounted at [workspaceMount]. Nothing outside those two folders is
 * reachable: an absolute link target starts at Linux's root, and `..` stops there.
 */
class LinuxFileSystem(
    /** The Linux root folder, or null while Linux isn't set up; workspace paths still resolve then. */
    private val root: () -> File?,
    private val workspaceMount: String,
    /** Live views of the device (/proc, /dev, /sys) mounted for commands; they aren't files in [root]. */
    private val deviceMounts: List<String>,
) {
    init {
        require(workspaceMount.startsWith(SEPARATOR) && workspaceMount != SEPARATOR) { "workspaceMount must be an absolute folder" }
    }

    /** [path] is absolute in Linux, or relative to the workspace mount. */
    fun resolve(workspace: File, path: String): Outcome<File> {
        val linuxRoot = root()
        val absolute = if (path.startsWith(SEPARATOR)) path else "$workspaceMount$SEPARATOR$path"
        return try {
            val linuxPath = when (val resolved = canonical(absolute, workspace, linuxRoot)) {
                is Outcome.Failure -> return resolved
                is Outcome.Success -> resolved.value
            }
            if (deviceMounts.any { linuxPath == it || linuxPath.startsWith(it + SEPARATOR) }) {
                return invalid("'$path' is a live view of the device; read it with run_command")
            }
            device(linuxPath, workspace, linuxRoot)?.let { Outcome.Success(it) } ?: notSetUp(path)
        } catch (exception: IOException) {
            invalid("'$path' can't be resolved: ${exception.message}")
        }
    }

    /** The path with every link followed, as Linux would: absolute links restart at Linux's root, `..` stops there. */
    private fun canonical(path: String, workspace: File, linuxRoot: File?): Outcome<String> {
        val pending = ArrayDeque(path.split(SEPARATOR))
        val resolved = ArrayList<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            when (val part = pending.removeFirst()) {
                "", CURRENT -> Unit
                PARENT -> resolved.removeLastOrNull()
                else -> {
                    resolved += part
                    val linuxPath = SEPARATOR + resolved.joinToString(SEPARATOR)
                    val file = device(linuxPath, workspace, linuxRoot) ?: return notSetUp(path)
                    if (Files.isSymbolicLink(file.toPath())) {
                        if (++links > MAX_LINKS) return invalid("'$path' has too many links")
                        val target = linuxTarget(Files.readSymbolicLink(file.toPath()).toString(), linuxRoot)
                        resolved.removeLast()
                        if (target.startsWith(SEPARATOR)) resolved.clear()
                        target.split(SEPARATOR).asReversed().forEach(pending::addFirst)
                    }
                }
            }
        }
        return Outcome.Success(SEPARATOR + resolved.joinToString(SEPARATOR))
    }

    /**
     * A link's target as a Linux path. proot records hard links (--link2symlink) as links to the device path of a
     * hidden file in [linuxRoot]; those are turned back into the Linux path they stand for.
     */
    private fun linuxTarget(target: String, linuxRoot: File?): String {
        if (linuxRoot == null || !target.startsWith(SEPARATOR)) return target
        val prefixes = listOf(linuxRoot.path, linuxRoot.canonicalPath).distinct()
        val prefix = prefixes.firstOrNull { target == it || target.startsWith(it + SEPARATOR) } ?: return target
        return target.removePrefix(prefix).ifEmpty { SEPARATOR }
    }

    /** Where a canonical Linux path lives on the device; null when it's in Linux and Linux isn't set up. */
    private fun device(linuxPath: String, workspace: File, linuxRoot: File?): File? = when {
        linuxPath == workspaceMount -> workspace
        linuxPath.startsWith(workspaceMount + SEPARATOR) -> File(workspace, linuxPath.removePrefix(workspaceMount + SEPARATOR))
        linuxRoot == null -> null
        else -> File(linuxRoot, linuxPath.removePrefix(SEPARATOR))
    }

    private fun notSetUp(path: String): Outcome<Nothing> =
        invalid("'$path' is in Linux, which isn't set up on this phone yet")

    private fun invalid(message: String): Outcome<Nothing> = Outcome.Failure(HarnessError.NotFound(message))

    private companion object {
        const val SEPARATOR = "/"
        const val CURRENT = "."
        const val PARENT = ".."
        /** Linux's own limit on links followed while resolving one path (MAXSYMLINKS). */
        const val MAX_LINKS = 40
    }
}
