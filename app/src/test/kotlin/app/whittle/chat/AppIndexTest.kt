package app.whittle.chat

import app.whittle.apps.AppRef
import app.whittle.storage.chats.ChatId
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIndexTest {
    private fun document(vararg items: ThreadItem) = ChatDocument(ChatDocument.SCHEMA_VERSION, title = "", items = items.toList(), history = emptyList(), activeRunId = null, nextKey = 0)

    @Test
    fun `apps are listed newest chat first, once per page, under their latest title`() {
        val older = ChatListItem(ChatId("a"), "Trip", updatedAtMillis = 1, pinned = false)
        val newer = ChatListItem(ChatId("b"), "Money", updatedAtMillis = 2, pinned = false)
        val index = AppIndex.from(
            listOf(
                older to document(ThreadItem.App("1", AppRef("a", "index.html", "Packing list"))),
                newer to document(
                    ThreadItem.App("2", AppRef("b", "index.html", "Tip calc")),
                    ThreadItem.App("3", AppRef("b", "split.html", "Splitter")),
                    ThreadItem.App("4", AppRef("b", "index.html", "Tip calculator")),
                ),
            ),
        )
        assertEquals(listOf("Tip calculator", "Splitter", "Packing list"), index.map { it.ref.title })
        assertEquals(listOf("Money", "Money", "Trip"), index.map { it.chatTitle })
    }
}
