package app.whittle.tools.workspace

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.tools.api.WorkspaceId
import java.io.File
import java.io.IOException

/** One folder per workspace under [root]. Every path a tool touches must resolve inside its workspace. */
class Workspaces(private val root: File) {
    fun directory(id: WorkspaceId): Outcome<File> {
        if (!SAFE_ID.matches(id.value)) return invalid("Workspace id '${id.value}' is not a plain name")
        val directory = File(root, id.value)
        if (!directory.isDirectory && !directory.mkdirs()) return Outcome.Failure(HarnessError.Storage("Can't create workspace ${id.value}"))
        return Outcome.Success(directory)
    }

    /** Removes a workspace and everything in it, e.g. when its chat is deleted. */
    fun delete(id: WorkspaceId): Outcome<Unit> {
        if (!SAFE_ID.matches(id.value)) return invalid("Workspace id '${id.value}' is not a plain name")
        val directory = File(root, id.value)
        return if (!directory.exists() || directory.deleteRecursively()) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(HarnessError.Storage("Can't delete workspace ${id.value}"))
        }
    }

    /** Resolves [path] (relative to the workspace) and refuses anything that lands outside it, symlinks included. */
    fun resolve(id: WorkspaceId, path: String): Outcome<File> {
        val directory = when (val dir = directory(id)) {
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

    private fun invalid(message: String): Outcome<Nothing> = Outcome.Failure(HarnessError.NotFound(message))

    private companion object {
        val SAFE_ID = Regex("""^[A-Za-z0-9_-]+$""")
    }
}
