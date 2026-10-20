package app.whittle.chat

import app.whittle.artifacts.ArtifactRef
import app.whittle.storage.chats.ChatId

/** An app the agent made, with the chat it came from. */
data class ArtifactListItem(val ref: ArtifactRef, val chatId: ChatId, val chatTitle: String?, val updatedAtMillis: Long)

/**
 * Every app across the user's chats, newest chat first. An app shown again in the same chat (the agent
 * updated it) is listed once, under its latest title.
 */
object ArtifactIndex {
    fun from(chats: List<Pair<ChatListItem, ChatDocument>>): List<ArtifactListItem> =
        chats.sortedByDescending { (chat, _) -> chat.updatedAtMillis }
            .flatMap { (chat, document) ->
                document.items.filterIsInstance<ThreadItem.Artifact>()
                    .asReversed()
                    .distinctBy { it.ref.path }
                    .map { ArtifactListItem(it.ref, chat.id, chat.title, chat.updatedAtMillis) }
            }
}
