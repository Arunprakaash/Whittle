package app.whittle.chat

import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import kotlinx.serialization.Serializable

/** What a saved chat holds: the thread as shown, the model-facing history, and any run still working. */
@Serializable
data class ChatDocument(
    val schemaVersion: Int,
    val title: String,
    val items: List<ThreadItem>,
    val history: List<Message>,
    /** Set while a run is working; still set on load means the app closed mid-run. */
    val activeRunId: RunId?,
    val nextKey: Long,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
