package app.whittle.chat.ui

import androidx.compose.runtime.Composable

/** When a Claude plan limit reopens: "2:00 PM" today, with the day when it's later than that. */
@Composable
expect fun reopensAt(millis: Long): String

/** "5 minutes ago": how long ago [millis] was, as the device says it. */
@Composable
expect fun timeAgo(millis: Long, nowMillis: Long): String

/** "212 KB", "1.8 MB": a size in the units people read. */
fun sizeText(bytes: Long): String = when {
    bytes < KILO -> "$bytes B"
    bytes < KILO * KILO -> "${(bytes + KILO / 2) / KILO} KB"
    bytes % (KILO * KILO) == 0L -> "${bytes / (KILO * KILO)} MB"
    else -> {
        val tenths = (bytes * TENTHS + KILO * KILO / 2) / (KILO * KILO)
        "${tenths / TENTHS}.${tenths % TENTHS} MB"
    }
}

/** The device's clock, in milliseconds since 1970. */
fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private const val KILO = 1024L
private const val TENTHS = 10L
