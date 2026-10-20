package app.whittle.tools.android

import android.content.Context
import android.provider.CalendarContract.Instances
import app.whittle.core.common.EpochClock
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Reads calendar events in a time range. Invites can be written by anyone, so event text is untrusted
 * and taints the run.
 */
class CalendarEventsTool(private val context: Context, private val clock: EpochClock) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        if (!AndroidCapabilities.isGranted(this.context, AndroidCapabilities.CALENDAR)) return ToolJson.missing(AndroidCapabilities.CALENDAR)
        val zone = ZoneId.systemDefault()
        val from = parse(arguments[FROM]?.jsonPrimitive?.contentOrNull, zone) ?: Instant.ofEpochMilli(clock.nowMillis())
        val to = parse(arguments[TO]?.jsonPrimitive?.contentOrNull, zone) ?: from.plusSeconds(DEFAULT_RANGE_SECONDS)
        if (!to.isAfter(from)) return ToolJson.error("'to' must be after 'from'")
        val events = withContext(Dispatchers.IO) { query(from, to) }
        return ToolJson.success(
            PROVENANCE,
            buildJsonObject {
                put(TIME_ZONE, zone.id)
                putJsonArray(EVENTS) {
                    events.forEach { event ->
                        addJsonObject {
                            put(TITLE, event.title)
                            put(START, LocalDateTime.ofInstant(event.start, zone).toString())
                            put(END, LocalDateTime.ofInstant(event.end, zone).toString())
                            put(ALL_DAY, event.allDay)
                            event.location?.let { put(LOCATION, it) }
                        }
                    }
                }
            },
        )
    }

    private fun query(from: Instant, to: Instant): List<Event> {
        val projection = arrayOf(Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION)
        Instances.query(context.contentResolver, projection, from.toEpochMilli(), to.toEpochMilli())?.use { cursor ->
            val title = cursor.getColumnIndexOrThrow(Instances.TITLE)
            val begin = cursor.getColumnIndexOrThrow(Instances.BEGIN)
            val end = cursor.getColumnIndexOrThrow(Instances.END)
            val allDay = cursor.getColumnIndexOrThrow(Instances.ALL_DAY)
            val location = cursor.getColumnIndexOrThrow(Instances.EVENT_LOCATION)
            return buildList {
                while (cursor.moveToNext() && size < MAX_EVENTS) {
                    add(
                        Event(
                            title = cursor.getString(title).orEmpty(),
                            start = Instant.ofEpochMilli(cursor.getLong(begin)),
                            end = Instant.ofEpochMilli(cursor.getLong(end)),
                            allDay = cursor.getInt(allDay) != 0,
                            location = cursor.getString(location)?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }.sortedBy { it.start }
        }
        return emptyList()
    }

    /** Accepts a date ("2026-10-20"), a local date-time, or a date-time with an offset. */
    private fun parse(value: String?, zone: ZoneId): Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
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

    private data class Event(val title: String, val start: Instant, val end: Instant, val allDay: Boolean, val location: String?)

    companion object {
        private const val FROM = "from"
        private const val TO = "to"
        private const val TIME_ZONE = "timeZone"
        private const val EVENTS = "events"
        private const val TITLE = "title"
        private const val START = "start"
        private const val END = "end"
        private const val ALL_DAY = "allDay"
        private const val LOCATION = "location"
        private const val MAX_EVENTS = 50
        private const val DEFAULT_RANGE_SECONDS = 24L * 60 * 60
        private val PROVENANCE = Provenance.Untrusted("your calendar")

        val DEFINITION = ToolDefinition(
            id = ToolId("calendar_events"),
            description = "Lists the user's calendar events between two times (ISO-8601 dates or date-times, in the user's " +
                "time zone unless an offset is given). Defaults to the next 24 hours.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$FROM":{"type":"string","description":"Start, e.g. 2026-10-20 or 2026-10-20T18:00"},""" +
                    """"$TO":{"type":"string","description":"End, exclusive"}}}""",
            ).jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = setOf(AndroidCapabilities.CALENDAR),
            consentTextKey = "consent_calendar_events",
            outputProvenance = PROVENANCE,
            exposesPersonalData = true,
        )
    }
}
