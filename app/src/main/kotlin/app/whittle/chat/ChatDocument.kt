package app.whittle.chat

import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    /** Every run this chat started, so deleting the chat deletes their checkpoints too. */
    val runIds: List<RunId> = emptyList(),
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 1
        private val JSON = Json { ignoreUnknownKeys = true }

        /** A chat from an incompatible version is skipped rather than shown half-read. */
        fun decode(document: String): ChatDocument? = try {
            JSON.decodeFromString(serializer(), document).takeIf { it.schemaVersion == SCHEMA_VERSION }
        } catch (exception: SerializationException) {
            null
        } catch (exception: IllegalArgumentException) {
            null
        }
    }
}
