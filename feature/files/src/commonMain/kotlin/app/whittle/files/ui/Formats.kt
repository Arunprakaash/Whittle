package app.whittle.files.ui

import androidx.compose.runtime.Composable
import kotlin.math.roundToLong

/** "5 minutes ago": how long ago [millis] was, as the device says it. */
@Composable
expect fun timeAgo(millis: Long, nowMillis: Long): String

/**
 * "212 kB", "1.8 MB", "1.4 GB": a size as Android writes one short, in thousands, with a tenth only below ten, so the
 * phone and a computer show the same.
 */
fun sizeText(bytes: Long): String {
    if (bytes < KILO) return "$bytes B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= KILO && unit < UNITS.lastIndex) {
        value /= KILO
        unit++
    }
    val tenths = (value * TENTHS).roundToLong()
    val number = if (tenths < TENTHS * TENTHS) "${tenths / TENTHS}.${tenths % TENTHS}" else value.roundToLong().toString()
    return "$number ${UNITS[unit]}"
}

/** The device's clock, in milliseconds since 1970. */
fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private const val KILO = 1000L
private const val TENTHS = 10L
private val UNITS = listOf("B", "kB", "MB", "GB", "TB")
