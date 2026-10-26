package app.whittle.automations.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// The phone's own formats, which follow its language and its 12- or 24-hour setting.
internal actual fun formatTime(minuteOfDay: Int): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.of(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR))

@Composable
internal actual fun uses24HourClock(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

internal actual fun shortDay(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, Locale.getDefault())

internal actual fun fullDay(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, Locale.getDefault())

internal actual fun narrowDay(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.NARROW, Locale.getDefault())
