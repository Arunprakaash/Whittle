package app.whittle.tools.api

import app.whittle.core.model.Provenance
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

data class ToolExecutionContext(
    val runId: RunId,
    val toolCallId: ToolCallId,
    /** The run's workspace; null when the caller gave it none, and workspace tools then refuse. */
    val workspace: WorkspaceId? = null,
    /** [CallPreview.approved] of the card the user approved for this call; null when no preview was approved. */
    val approved: List<String>? = null,
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

    /**
     * What this call would do, checked by the tool itself for its approval card, such as which files a delete would
     * remove and how big they are. Null (the default) for tools whose arguments say it all. Called when the call is
     * parked for the user; never acts.
     */
    suspend fun preview(arguments: JsonObject): CallPreview? = null
}

/**
 * A tool's own account of a call, kept with the parked call. [card] fills the card's placeholders, after the call's
 * arguments so none can stand in for them; [variant] picks another card for the tool, `<tool>.<variant>` in the
 * presentation config, such as one saying nothing will happen. [approved] is what the tool may act on once the user
 * approves this card, handed back in [ToolExecutionContext.approved]; anything else is refused then.
 */
@Serializable
data class CallPreview(
    val card: Map<String, String>,
    val approved: List<String>,
    val variant: String? = null,
)

