package app.whittle.memory.ui

import androidx.compose.runtime.Composable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** When a memory was saved, briefly: the time today, "Yesterday", the weekday this week, else the date. */
@Composable
internal expect fun savedWhen(millis: Long, nowMillis: Long): String

/** The date and time a memory was saved, in full. */
internal expect fun savedOn(millis: Long): String

/** Whether [millis] falls on the same day as [nowMillis], where this device is. */
internal fun isToday(millis: Long, nowMillis: Long): Boolean {
    val zone = TimeZone.currentSystemDefault()
    return Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date == Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
}

/** Within this many days, a memory's date is shown as its weekday. */
internal const val DAYS_SHOWN_BY_NAME = 7L
