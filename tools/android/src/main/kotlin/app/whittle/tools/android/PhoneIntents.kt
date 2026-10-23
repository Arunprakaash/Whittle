package app.whittle.tools.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Hands [intent] to the app that handles it, from the main thread. False when no app on the phone handles it, or Android
 * refuses the start.
 */
suspend fun Context.startHandOff(intent: Intent): Boolean = withContext(Dispatchers.Main) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (exception: ActivityNotFoundException) {
        false
    } catch (exception: SecurityException) {
        false
    }
}

/** A text argument, trimmed; empty when missing. */
fun JsonObject.text(name: String): String = this[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

/** Accepts a date ("2026-10-20"), a local date-time ("2026-10-20T18:00"), or a date-time with an offset. */
fun parseTime(value: String, zone: ZoneId): Instant? {
    val text = value.trim().takeIf { it.isNotEmpty() } ?: return null
    return listOf<(String) -> Instant>(
        { OffsetDateTime.parse(it).toInstant() },
        { LocalDateTime.parse(it).atZone(zone).toInstant() },
        { LocalDate.parse(it).atStartOfDay(zone).toInstant() },
    ).firstNotNullOfOrNull { parser ->
        try {
            parser(text)
        } catch (exception: DateTimeParseException) {
            null
        }
    }
}
