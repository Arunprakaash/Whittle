package app.whittle.files.ui

import androidx.compose.runtime.Composable

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
