package app.whittle.chat

import app.whittle.apps.AppRef
import app.whittle.storage.chats.ChatId

/** An app the agent made, with the chat it came from. */
data class AppListItem(val ref: AppRef, val chatId: ChatId, val chatTitle: String?, val updatedAtMillis: Long)

/**
 * Every app across the user's chats, newest chat first. An app shown again in the same chat (the agent
 * updated it) is listed once, under its latest title.
 */
object AppIndex {
    fun from(chats: List<Pair<ChatListItem, ChatDocument>>): List<AppListItem> =
        chats.sortedByDescending { (chat, _) -> chat.updatedAtMillis }
            .flatMap { (chat, document) ->
                document.items.filterIsInstance<ThreadItem.App>()
                    .asReversed()
                    .distinctBy { it.ref.path }
                    .map { AppListItem(it.ref, chat.id, chat.title, chat.updatedAtMillis) }
            }
}
