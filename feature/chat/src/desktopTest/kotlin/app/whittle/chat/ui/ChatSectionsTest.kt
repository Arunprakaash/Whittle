package app.whittle.chat.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSectionsTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private val now = at(2026, 10, 20, 12, 0)

    @Test
    fun `chats fall into pinned and calendar-day sections`() {
        val chats = listOf(
            chat("old", at(2026, 8, 1, 9, 0)),
            chat("pinned", at(2026, 1, 1, 9, 0), pinned = true),
            chat("today-early", at(2026, 10, 20, 0, 5)),
            chat("yesterday-late", at(2026, 10, 19, 23, 55)),
            chat("week", at(2026, 10, 14, 10, 0)),
            chat("month", at(2026, 10, 1, 10, 0)),
            chat("today-late", at(2026, 10, 20, 11, 0)),
        )

        val groups = ChatSections.group(chats, now, zone)

        assertEquals(
            listOf(
                ChatSection.Pinned to listOf("pinned"),
                ChatSection.Today to listOf("today-late", "today-early"),
                ChatSection.Yesterday to listOf("yesterday-late"),
                ChatSection.Previous7Days to listOf("week"),
                ChatSection.Previous30Days to listOf("month"),
                ChatSection.Older to listOf("old"),
            ),
            groups.map { group -> group.section to group.chats.map { it.id } },
        )
    }

    @Test
    fun `the sidebar is capped`() {
        val recent = (1..30).map { chat("c$it", now - it * MINUTE) }
        val groups = ChatSections.group(recent + chat("old", at(2026, 1, 1, 9, 0)), now, zone)

        val sidebar = ChatSections.sidebar(groups, maxChats = 25)

        assertEquals(25, sidebar.sumOf { it.chats.size })
    }

    @Test
    fun `older chats only appear on the chats page`() {
        val groups = ChatSections.group(listOf(chat("today", now), chat("old", at(2026, 1, 1, 9, 0))), now, zone)

        val sidebar = ChatSections.sidebar(groups, maxChats = 25)

        assertEquals(listOf("today"), sidebar.flatMap { it.chats }.map { it.id })
    }

    private fun chat(id: String, updatedAt: Long, pinned: Boolean = false) = ChatEntry(id, id, updatedAt, pinned)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private companion object {
        const val MINUTE = 60_000L
    }
}
