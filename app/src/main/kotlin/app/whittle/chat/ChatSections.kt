package app.whittle.chat

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Where a chat sits in the list, in display order. */
enum class ChatSection { Pinned, Today, Yesterday, Previous7Days, Previous30Days, Older }

data class ChatGroup(val section: ChatSection, val chats: List<ChatListItem>)

object ChatSections {
    /** Pinned first, then by the calendar day each chat was last updated, newest first within a section. */
    fun group(chats: List<ChatListItem>, nowMillis: Long, zone: ZoneId): List<ChatGroup> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val (pinned, rest) = chats.partition { it.pinned }
        val dated = rest.groupBy { chat ->
            val day = Instant.ofEpochMilli(chat.updatedAtMillis).atZone(zone).toLocalDate()
            sectionFor(ChronoUnit.DAYS.between(day, today))
        }
        return ChatSection.entries.mapNotNull { section ->
            val members = if (section == ChatSection.Pinned) pinned else dated[section].orEmpty()
            members.takeIf { it.isNotEmpty() }?.let { ChatGroup(section, it.sortedByDescending(ChatListItem::updatedAtMillis)) }
        }
    }

    /** Only pinned chats and the last week, at most [maxChats] of them; everything else is on the Chats page. */
    fun sidebar(groups: List<ChatGroup>, maxChats: Int): List<ChatGroup> {
        var remaining = maxChats
        return groups.filter { it.section in SIDEBAR_SECTIONS }.mapNotNull { group ->
            val kept = group.chats.take(remaining)
            remaining -= kept.size
            kept.takeIf { it.isNotEmpty() }?.let { group.copy(chats = it) }
        }
    }

    private fun sectionFor(daysAgo: Long): ChatSection = when {
        daysAgo <= 0 -> ChatSection.Today
        daysAgo == 1L -> ChatSection.Yesterday
        daysAgo <= WEEK_DAYS -> ChatSection.Previous7Days
        daysAgo <= MONTH_DAYS -> ChatSection.Previous30Days
        else -> ChatSection.Older
    }

    private val SIDEBAR_SECTIONS = setOf(ChatSection.Pinned, ChatSection.Today, ChatSection.Yesterday, ChatSection.Previous7Days)
    private const val WEEK_DAYS = 7L
    private const val MONTH_DAYS = 30L
}
