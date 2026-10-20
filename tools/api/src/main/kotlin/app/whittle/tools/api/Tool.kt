package app.whittle.tools.api

import app.whittle.core.model.Provenance
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import kotlinx.serialization.json.JsonObject

data class ToolExecutionContext(
    val runId: RunId,
    val toolCallId: ToolCallId,
    /** The run's workspace; null when the caller gave it none, and workspace tools then refuse. */
    val workspace: WorkspaceId? = null,
)

data class ToolOutput(
    val content: String,
    val isError: Boolean,
    val provenance: Provenance,
    /** Set when the tool couldn't run because the user hasn't granted this capability yet. */
    val missingCapability: CapabilityId? = null,
)

/**
 * A tool that asks the user to choose instead of acting. The harness shows it as an inline card and
 * never calls [execute]; the user's choice goes back to the model as a note.
 */
interface UserQuestionTool : Tool {
    /** The question text, for the note that reports the answer. */
    fun question(arguments: JsonObject): String
}

/** A capability the agent can call. Implementations register themselves through DI. */
interface Tool {
    val definition: ToolDefinition

    suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput
}
