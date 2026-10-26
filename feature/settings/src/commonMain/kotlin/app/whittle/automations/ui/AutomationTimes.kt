package app.whittle.automations.ui

import androidx.compose.runtime.Composable
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** A time of day in the device's style: "07:30" or "7:30 AM". */
internal expect fun formatTime(minuteOfDay: Int): String

/** Whether the device shows the time on a 24-hour clock, as its time picker should. */
@Composable
internal expect fun uses24HourClock(): Boolean

/** "Mon"; "Monday"; "M": an ISO weekday's name in the device's language. */
internal expect fun shortDay(isoDay: Int): String

internal expect fun fullDay(isoDay: Int): String

internal expect fun narrowDay(isoDay: Int): String

internal const val MINUTES_PER_HOUR = 60
internal val EVERY_DAY: Set<Int> = DayOfWeek.entries.map { it.isoDayNumber }.toSet()
internal val WEEKDAYS: Set<Int> = (DayOfWeek.MONDAY.isoDayNumber..DayOfWeek.FRIDAY.isoDayNumber).toSet()
internal val WEEKENDS: Set<Int> = setOf(DayOfWeek.SATURDAY.isoDayNumber, DayOfWeek.SUNDAY.isoDayNumber)
private const val DAYS_PER_WEEK = 7

/**
 * The first time a schedule is due after [afterMillis], in [zone]: as the phone works it out, so a computer's editor
 * says the same "next" before the change is saved.
 */
internal fun nextRun(minuteOfDay: Int, days: Set<Int>, zone: String, afterMillis: Long): Long? {
    val timeZone = timeZone(zone)
    val today = Instant.fromEpochMilliseconds(afterMillis).toLocalDateTime(timeZone).date
    val time = LocalTime(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)
    return (0..DAYS_PER_WEEK).asSequence()
        .map { today.plus(DatePeriod(days = it)) }
        .filter { it.dayOfWeek.isoDayNumber in days }
        .map { LocalDateTime(it, time).toInstant(timeZone).toEpochMilliseconds() }
        .firstOrNull { it > afterMillis }
}

/** "today, 07:30", "tomorrow, 07:30", "Thu, 07:30": [millis] in [zone], next to [nowMillis]. */
@Composable
internal fun dayAndTime(millis: Long, zone: String, nowMillis: Long): String {
    val timeZone = timeZone(zone)
    val at = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date
    val day = when (at.date) {
        today -> stringResource(Res.string.automation_today)
        today.plus(DatePeriod(days = 1)) -> stringResource(Res.string.automation_tomorrow)
        today.plus(DatePeriod(days = -1)) -> stringResource(Res.string.automation_yesterday)
        else -> shortDay(at.dayOfWeek.isoDayNumber)
    }
    return stringResource(Res.string.automation_day_at, day, formatTime(at.hour * MINUTES_PER_HOUR + at.minute))
}

/** "Every day", "Weekdays", "Weekends" or "Mon, Wed". */
@Composable
internal fun daysText(days: Set<Int>): String = when (days) {
    EVERY_DAY -> stringResource(Res.string.automation_every_day)
    WEEKDAYS -> stringResource(Res.string.automation_weekdays)
    WEEKENDS -> stringResource(Res.string.automation_weekends)
    else -> days.sorted().joinToString(", ") { shortDay(it) }
}

/** "Weekdays at 07:30". */
@Composable
internal fun scheduleText(minuteOfDay: Int, days: Set<Int>): String =
    stringResource(Res.string.automation_schedule, daysText(days), formatTime(minuteOfDay))

/** The phone's zone, or this device's when it names one this device doesn't know. */
private fun timeZone(zone: String): TimeZone = runCatching { TimeZone.of(zone) }.getOrElse { TimeZone.currentSystemDefault() }
