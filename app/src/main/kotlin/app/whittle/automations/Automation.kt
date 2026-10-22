package app.whittle.automations

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A task Whittle runs by itself on a schedule, in a chat of its own. [tools] are the ones it may use without
 * asking; anything else waits for the user's approval, as in any unattended run.
 */
@Serializable
data class Automation(
    val id: String,
    val name: String,
    /** What Whittle is told each time, as if the user had sent it. */
    val prompt: String,
    val schedule: Schedule,
    val tools: Set<String>,
    val enabled: Boolean = true,
    val lastRunAtMillis: Long? = null,
    /** The chat the last run went to, so the user can open what it found. */
    val lastChatId: String? = null,
)

/** A time of day on some days of the week, in the phone's time zone at the moment it's due. */
@Serializable
data class Schedule(
    val minuteOfDay: Int,
    /** ISO days of the week: 1 is Monday, 7 is Sunday. Never empty. */
    val days: Set<Int>,
) {
    init {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay must be within a day" }
        require(days.isNotEmpty() && days.all { it in DayOfWeek.MONDAY.value..DayOfWeek.SUNDAY.value }) { "days must name days of the week" }
    }

    val time: LocalTime get() = LocalTime.of(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)

    val daysOfWeek: List<DayOfWeek> get() = days.sorted().map(DayOfWeek::of)

    /**
     * The first time it's due strictly after [after]. A time that doesn't exist on a day (clocks went forward)
     * moves forward with the clock, the way an alarm clock does.
     */
    fun nextAfter(after: Instant, zone: ZoneId): Instant {
        val today = after.atZone(zone).toLocalDate()
        return (0..DAYS_PER_WEEK).asSequence()
            .map { today.plusDays(it.toLong()) }
            .filter { it.dayOfWeek.value in days }
            .map { it.atTime(time).atZone(zone).toInstant() }
            .first { it.isAfter(after) }
    }

    companion object {
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
        private const val DAYS_PER_WEEK = 7
        val EVERY_DAY: Set<Int> = DayOfWeek.entries.map { it.value }.toSet()
        val WEEKDAYS: Set<Int> = (DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value).toSet()
        val WEEKENDS: Set<Int> = setOf(DayOfWeek.SATURDAY.value, DayOfWeek.SUNDAY.value)

        fun at(time: LocalTime, days: Set<Int>) = Schedule(time.hour * MINUTES_PER_HOUR + time.minute, days)
    }
}
