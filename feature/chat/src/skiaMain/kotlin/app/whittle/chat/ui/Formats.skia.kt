package app.whittle.chat.ui

import androidx.compose.runtime.Composable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
actual fun reopensAt(millis: Long): String {
    val zone = TimeZone.currentSystemDefault()
    val at = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(zone).date
    val time = "${at.hour}:${at.minute.toString().padStart(2, '0')}"
    return if (at.date == today) time else "${at.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} $time"
}

@Composable
actual fun timeAgo(millis: Long, nowMillis: Long): String {
    val minutes = ((nowMillis - millis) / MILLIS_PER_MINUTE).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"
        else -> "${minutes / MINUTES_PER_DAY} d ago"
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 1_440L
