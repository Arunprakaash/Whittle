package app.whittle.chat.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Where a chat sits in the list, in display order. */
enum class ChatSection { Pinned, Today, Yesterday, Previous7Days, Previous30Days, Older }

data class ChatGroup(val section: ChatSection, val chats: List<ChatEntry>)

object ChatSections {
    /** Pinned first, then by the calendar day each chat was last updated, newest first within a section. */
    fun group(chats: List<ChatEntry>, nowMillis: Long, zone: TimeZone): List<ChatGroup> {
        val today = day(nowMillis, zone)
        val (pinned, rest) = chats.partition { it.pinned }
        val dated = rest.groupBy { chat -> sectionFor(day(chat.updatedAtMillis, zone).daysUntil(today).toLong()) }
        return ChatSection.entries.mapNotNull { section ->
            val members = if (section == ChatSection.Pinned) pinned else dated[section].orEmpty()
            members.takeIf { it.isNotEmpty() }?.let { ChatGroup(section, it.sortedByDescending(ChatEntry::updatedAtMillis)) }
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

    private fun day(millis: Long, zone: TimeZone) = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

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
