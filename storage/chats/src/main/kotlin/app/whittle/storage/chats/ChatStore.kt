package app.whittle.storage.chats

import app.whittle.core.common.Outcome
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ChatId(val value: String)

/** One chat as the app saved it. [document] is the app's own format; the store only encrypts it. */
data class StoredChat(val id: ChatId, val updatedAtMillis: Long, val document: String)

/** Chats kept on this phone only, encrypted at rest. */
interface ChatStore {
    suspend fun save(id: ChatId, document: String): Outcome<Unit>

    /** Returns null when there is no chat with [id]. */
    suspend fun load(id: ChatId): Outcome<StoredChat?>

    /** The most recently saved chat, or null before the first one. */
    suspend fun latest(): Outcome<StoredChat?>
}
