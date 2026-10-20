package app.whittle.tools.workspace

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

/**
 * Saves long tool outputs in a hidden folder of the chat's workspace, one file per call, where the agent can
 * read them back with its workspace tools. Hidden folders stay out of file listings, builds and exports.
 * Tools report as JSON, whose text fields hold their line breaks as `\n`; those are written out as real lines,
 * so grep and sed work on the file.
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
                File(root, path).apply { parentFile?.mkdirs() }.writeText(readable(content))
                Outcome.Success(path)
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Storage("Couldn't save the output: ${exception.message}"))
            }
        }
    }

    /**
     * A JSON object becomes one section per field: text fields as their own lines under a `[field]` heading,
     * other values on the heading line. Anything else is kept as it is.
     */
    private fun readable(content: String): String {
        val fields = try {
            Json.parseToJsonElement(content) as? JsonObject
        } catch (exception: SerializationException) {
            null
        } ?: return content
        return fields.entries.joinToString(SECTION_BREAK) { (key, value) ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text != null) "[$key]\n$text" else "[$key] $value"
        }
    }

    /** Ids come from providers; only plain characters go into a file name. */
    private fun safe(id: String): String = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(MAX_ID_CHARS)

    private companion object {
        const val EXTENSION = ".txt"
        const val SECTION_BREAK = "\n\n"
        const val MAX_ID_CHARS = 64
    }
}
