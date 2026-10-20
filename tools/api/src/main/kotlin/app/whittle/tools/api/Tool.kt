package app.whittle.tools.api

import app.whittle.core.model.Provenance
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import kotlinx.serialization.json.JsonObject

data class ToolExecutionContext(
    val runId: RunId,
    val toolCallId: ToolCallId,
)

data class ToolOutput(
    val content: String,
    val isError: Boolean,
    val provenance: Provenance,
)

/** A capability the agent can call. Implementations register themselves through DI. */
interface Tool {
    val definition: ToolDefinition

    suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput
}
