package app.whittle.chat.ui

import app.whittle.files.ui.nowMillis
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
