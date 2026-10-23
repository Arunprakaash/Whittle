package app.whittle.agent

import app.whittle.core.common.Outcome
import app.whittle.core.model.ContentBlock
import app.whittle.tools.api.WorkspaceId

/**
 * Reads an attached image from the chat's workspace, sized for a model, when a request is about to be sent.
 * The host decides the size and format; the conversation itself only ever holds the file's path.
 */
fun interface ImageSource {
    /** The image at [path] (relative to the workspace root), ready to send. */
    suspend fun load(workspace: WorkspaceId, path: String): Outcome<ContentBlock.Image>
}
