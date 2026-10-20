package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** Shared plumbing: every workspace tool answers in compact JSON and works inside the run's workspace. */
abstract class WorkspaceTool(protected val workspaces: Workspaces) : Tool {
    final override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val workspace = context.workspace ?: return error("this run has no workspace")
        return run(arguments, workspace.let { id -> WorkspaceAccess(id, workspaces) })
    }

    protected abstract suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput

    protected fun success(body: JsonObject, provenance: Provenance = Provenance.Trusted): ToolOutput =
        ToolOutput(body.toString(), isError = false, provenance = provenance)

    protected fun error(reason: String): ToolOutput =
        ToolOutput(buildJsonObject { put(ERROR, reason) }.toString(), isError = true, provenance = Provenance.Trusted)

    protected fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    protected fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

    private companion object {
        const val ERROR = "error"
    }
}

/** A run's view of its workspace. */
class WorkspaceAccess(private val id: app.whittle.tools.api.WorkspaceId, private val workspaces: Workspaces) {
    fun root(): Outcome<File> = workspaces.directory(id)

    fun file(path: String): Outcome<File> = workspaces.resolve(id, path)
}
