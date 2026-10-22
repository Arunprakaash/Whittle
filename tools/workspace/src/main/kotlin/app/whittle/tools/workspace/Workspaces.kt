package app.whittle.tools.workspace

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.tools.api.WorkspaceId
import java.io.File
import java.io.IOException

/**
 * The one folder every chat works in, [root], mounted at /workspace in Linux. A [WorkspaceId] names the chat that
 * is working, for what belongs to the chat (its processes, its saved outputs, what it changed); the files are
 * shared, so a chat can start several projects and another chat can carry on with them. Every path a tool touches
 * must resolve inside the workspace, or, with [linux], inside the Linux that commands run in.
 */
class Workspaces(
    private val root: File,
    private val linux: LinuxFileSystem? = null,
    private val projects: ChatProjects = ChatProjects.NONE,
) {
    /** The folder [id] works in, relative to the workspace, while it still exists; null for the whole workspace. */
    fun projectOf(id: WorkspaceId): String? = projects.projectOf(id)?.takeIf { File(root, it).isDirectory }

    /** The shared folder, for the chat [id]. */
    fun directory(id: WorkspaceId): Outcome<File> {
        if (!SAFE_ID.matches(id.value)) return invalid("Workspace id '${id.value}' is not a plain name")
        return folder()
    }

    /** The shared folder, created if it isn't there yet. */
    fun folder(): Outcome<File> {
        if (!root.isDirectory && !root.mkdirs()) return Outcome.Failure(HarnessError.Storage("Can't create the workspace"))
        return Outcome.Success(root)
    }

    /** Resolves [path] (relative to the workspace) for the chat [id]; see the other [resolve]. */
    fun resolve(id: WorkspaceId, path: String): Outcome<File> {
        if (!SAFE_ID.matches(id.value)) return invalid("Workspace id '${id.value}' is not a plain name")
        return resolve(inProject(id, path))
    }

    /** Resolves [path] (relative to the workspace) and refuses anything that lands outside it, symlinks included. */
    fun resolve(path: String): Outcome<File> {
        val directory = when (val dir = folder()) {
            is Outcome.Failure -> return dir
            is Outcome.Success -> dir.value
        }
        if (path.startsWith("/")) return invalid("Use a path relative to the workspace, not '$path'")
        return try {
            val base = directory.canonicalFile
            val target = File(base, path).canonicalFile
            if (target == base || target.path.startsWith(base.path + File.separator)) Outcome.Success(target) else invalid("'$path' is outside the workspace")
        } catch (exception: IOException) {
            invalid("'$path' can't be resolved: ${exception.message}")
        }
    }

    /**
     * A path the model gave a file tool: a Linux path when there is a Linux, else one relative to the workspace. A
     * confined chat ([ChatProjects.isConfined]) only reaches its project's folder.
     */
    fun locate(id: WorkspaceId, path: String): Outcome<File> {
        val linux = linux ?: return confined(id, path, resolve(id, path))
        return when (val directory = directory(id)) {
            is Outcome.Failure -> directory
            is Outcome.Success -> confined(id, path, linux.resolve(directory.value, inProject(id, path)))
        }
    }

    private fun confined(id: WorkspaceId, path: String, located: Outcome<File>): Outcome<File> {
        if (located !is Outcome.Success || !projects.isConfined(id)) return located
        val project = projects.projectOf(id) ?: return invalid("This chat is limited to a project, but it has none")
        return try {
            val base = File(root, project).canonicalFile
            val target = located.value.canonicalFile
            if (target == base || target.path.startsWith(base.path + File.separator)) located else invalid("'$path' is outside the project '$project', which this chat is limited to")
        } catch (exception: IOException) {
            invalid("'$path' can't be resolved: ${exception.message}")
        }
    }

    /** A relative path, inside the chat's project when it has one; absolute paths are left as they are. */
    private fun inProject(id: WorkspaceId, path: String): String {
        val project = projectOf(id) ?: return path
        return if (path.startsWith("/")) path else "$project/$path"
    }

    private fun invalid(message: String): Outcome<Nothing> = Outcome.Failure(HarnessError.NotFound(message))

    private companion object {
        val SAFE_ID = Regex("""^[A-Za-z0-9_-]+$""")
    }
}
