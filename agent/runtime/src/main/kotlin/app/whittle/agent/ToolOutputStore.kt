package app.whittle.agent

import app.whittle.core.common.Outcome
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.WorkspaceId

/**
 * Keeps a tool's whole output on the phone when it's too long to put in front of the model. The model gets
 * the start and end, and the path to read the rest from.
 */
fun interface ToolOutputStore {
    /** Saves [content] in [workspace] and returns its path relative to the workspace root. */
    suspend fun save(workspace: WorkspaceId, runId: RunId, callId: ToolCallId, content: String): Outcome<String>
}
