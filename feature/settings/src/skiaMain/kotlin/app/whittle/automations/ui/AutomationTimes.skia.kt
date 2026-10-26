package app.whittle.automations.ui

import androidx.compose.runtime.Composable

// A computer has no shared formatter for every target, so these are written out, on a 24-hour clock.
internal actual fun formatTime(minuteOfDay: Int): String =
    "${(minuteOfDay / MINUTES_PER_HOUR).toString().padStart(2, '0')}:${(minuteOfDay % MINUTES_PER_HOUR).toString().padStart(2, '0')}"

@Composable
internal actual fun uses24HourClock(): Boolean = true

internal actual fun shortDay(isoDay: Int): String = FULL_DAYS[isoDay - 1].take(SHORT_DAY)

internal actual fun fullDay(isoDay: Int): String = FULL_DAYS[isoDay - 1]

internal actual fun narrowDay(isoDay: Int): String = FULL_DAYS[isoDay - 1].take(1)

private val FULL_DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private const val SHORT_DAY = 3
