package app.whittle.storage.chats

import app.whittle.core.common.Outcome
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ChatId(val value: String)

/** One chat as the app saved it. [document] is the app's own format; the store only encrypts it. */
data class StoredChat(val id: ChatId, val updatedAtMillis: Long, val document: String)

/** What the chat list needs, without decrypting whole chats. [title] is null for chats saved before titles were stored. */
data class ChatSummary(val id: ChatId, val title: String?, val updatedAtMillis: Long, val pinned: Boolean)

/** Chats kept on this phone only, encrypted at rest. */
interface ChatStore {
    suspend fun save(id: ChatId, title: String, document: String): Outcome<Unit>

    /** Saves a chat that keeps its own last-updated time, e.g. one restored from a backup. */
    suspend fun importChat(id: ChatId, title: String, document: String, updatedAtMillis: Long): Outcome<Unit>

    /** Returns null when there is no chat with [id]. */
    suspend fun load(id: ChatId): Outcome<StoredChat?>

    /** The most recently saved chat, or null before the first one. */
    suspend fun latest(): Outcome<StoredChat?>

    /** Every chat, newest first. */
    suspend fun list(): Outcome<List<ChatSummary>>

    suspend fun delete(id: ChatId): Outcome<Unit>

    /** Pinning never changes when a chat was last updated, so it doesn't reorder anything else. */
    suspend fun setPinned(id: ChatId, pinned: Boolean): Outcome<Unit>
}
