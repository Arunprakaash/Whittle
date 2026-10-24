package app.whittle.chat

import app.whittle.quickstart.QuickStart
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
    fun `a shortcut opens its app only when it came through the quick start alias`() {
        val open = AppShortcuts.ACTION_OPEN_APP
        assertEquals(ShortcutOpening(ChatId("c-1"), "p-2"), AppShortcuts.opening(open, QuickStart.ENTRY, "c-1", "p-2"))
        // MainActivity is exported: another app can send the same action and extras straight to it.
        assertNull(AppShortcuts.opening(open, "app.whittle.MainActivity", "c-1", "p-2"))
        assertNull(AppShortcuts.opening(open, null, "c-1", "p-2"))
        assertNull(AppShortcuts.opening("other", QuickStart.ENTRY, "c-1", "p-2"))
        assertNull(AppShortcuts.opening(open, QuickStart.ENTRY, "c-1", null))
    }

    @Test
    fun `the icon shows the name's first letter or digit`() {
        assertEquals("T", iconLetter("todo list"))
        assertEquals("2", iconLetter(" 2048"))
        assertEquals("W", iconLetter("✨"))
        assertEquals("W", iconLetter(""))
    }
}
