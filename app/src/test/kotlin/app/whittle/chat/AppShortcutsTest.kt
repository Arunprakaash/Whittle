package app.whittle.chat

import app.whittle.storage.chats.ChatId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppShortcutsTest {
    @Test
    fun `a shortcut id names its chat and card, and gives the chat back`() {
        val id = shortcutId(ChatId("c-42"), "process-7")
        assertEquals("app:c-42:process-7", id)
        assertEquals(ChatId("c-42"), chatOf(id))
    }

    @Test
    fun `ids that aren't app shortcuts belong to no chat`() {
        assertNull(chatOf("new-chat"))
        assertNull(chatOf("app:"))
        assertNull(chatOf("app::process-7"))
    }

    @Test
    fun `the icon shows the name's first letter or digit`() {
        assertEquals("T", iconLetter("todo list"))
        assertEquals("2", iconLetter(" 2048"))
        assertEquals("W", iconLetter("✨"))
        assertEquals("W", iconLetter(""))
    }
}
