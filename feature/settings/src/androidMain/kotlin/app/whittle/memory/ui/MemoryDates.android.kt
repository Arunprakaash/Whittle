package app.whittle.memory.ui

import androidx.compose.runtime.Composable
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.memory_yesterday
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// The phone's own formats, which follow its language.
@Composable
internal actual fun savedWhen(millis: Long, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val day = at.toLocalDate()
    return when {
        day == today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
        day == today.minusDays(1) -> stringResource(Res.string.memory_yesterday)
        day.isAfter(today.minusDays(DAYS_SHOWN_BY_NAME)) -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(at)
    }
}

internal actual fun savedOn(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
