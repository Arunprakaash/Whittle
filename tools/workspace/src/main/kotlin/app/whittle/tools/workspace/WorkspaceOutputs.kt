package app.whittle.tools.workspace

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves long tool outputs in a hidden folder of the chat's workspace, one file per call, where the agent can
 * read them back with its workspace tools. Hidden folders stay out of file listings, builds and exports.
 */
class WorkspaceOutputs(private val workspaces: Workspaces, private val directory: String) {
    suspend fun save(workspace: WorkspaceId, runId: RunId, callId: ToolCallId, content: String): Outcome<String> {
        val root = when (val resolved = workspaces.directory(workspace)) {
            is Outcome.Success -> resolved.value
            is Outcome.Failure -> return resolved
        }
        val path = "$directory/${safe(runId.value)}-${safe(callId.value)}$EXTENSION"
        return withContext(Dispatchers.IO) {
            try {
                File(root, path).apply { parentFile?.mkdirs() }.writeText(content)
                Outcome.Success(path)
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Storage("Couldn't save the output: ${exception.message}"))
            }
        }
    }

    /** Ids come from providers; only plain characters go into a file name. */
    private fun safe(id: String): String = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(MAX_ID_CHARS)

    private companion object {
        const val EXTENSION = ".txt"
        const val MAX_ID_CHARS = 64
    }
}
