package app.whittle.tools.android

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Adds an event to one of the user's calendars on this phone. Finding the calendars needs reading them too, so it asks
 * for both. The first time, Android's own prompt is the user's say; after that it adds without asking.
 */
class AddCalendarEventTool(private val context: Context) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        DEFINITION.requiredCapabilities.firstOrNull { !AndroidCapabilities.isGranted(this.context, it) }?.let { return ToolJson.missing(it) }
        val title = arguments.text(TITLE)
        if (title.isEmpty()) return ToolJson.error("title is empty")
        val zone = ZoneId.systemDefault()
        val start = parseTime(arguments.text(START), zone) ?: return ToolJson.error("start must be an ISO-8601 date-time, e.g. 2026-10-30T16:00")
        val end = arguments.text(END).takeIf { it.isNotEmpty() }?.let { parseTime(it, zone) ?: return ToolJson.error("end must be an ISO-8601 date-time") }
            ?: start.plus(DEFAULT_LENGTH)
        if (!end.isAfter(start)) return ToolJson.error("end must be after start")
        val calendar = withContext(Dispatchers.IO) { pickCalendar(arguments.text(CALENDAR)) }
            ?: return ToolJson.error("there's no calendar on this phone that events can be added to")
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar.id)
            put(Events.TITLE, title)
            put(Events.DTSTART, start.toEpochMilli())
            put(Events.DTEND, end.toEpochMilli())
            put(Events.EVENT_TIMEZONE, zone.id)
            arguments.text(LOCATION).takeIf { it.isNotEmpty() }?.let { put(Events.EVENT_LOCATION, it) }
            arguments.text(NOTES).takeIf { it.isNotEmpty() }?.let { put(Events.DESCRIPTION, it) }
        }
        val added = withContext(Dispatchers.IO) { this@AddCalendarEventTool.context.contentResolver.insert(Events.CONTENT_URI, values) }
            ?: return ToolJson.error("the calendar didn't take the event")
        return ToolJson.success(
            Provenance.Trusted,
            buildJsonObject {
                put(ADDED, true)
                put(EVENT_ID, added.lastPathSegment.orEmpty())
                put(CALENDAR, calendar.name)
                put(START, LocalDateTime.ofInstant(start, zone).toString())
                put(END, LocalDateTime.ofInstant(end, zone).toString())
            },
        )
    }

    /** The calendar named [wanted], else the primary one events can be added to, else the first such. */
    private fun pickCalendar(wanted: String): Calendar? {
        val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.IS_PRIMARY)
        val writable = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1"
        val calendars = context.contentResolver.query(Calendars.CONTENT_URI, projection, writable, arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Calendar(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getInt(2) != 0))
                }
            }.orEmpty()
        return calendars.firstOrNull { wanted.isNotEmpty() && it.name.equals(wanted, ignoreCase = true) }
            ?: calendars.firstOrNull { it.primary }
            ?: calendars.firstOrNull()
    }

    private data class Calendar(val id: Long, val name: String, val primary: Boolean)

    companion object {
        private const val TITLE = "title"
        private const val START = "start"
        private const val END = "end"
        private const val LOCATION = "location"
        private const val NOTES = "notes"
        private const val CALENDAR = "calendar"
        private const val ADDED = "added"
        private const val EVENT_ID = "eventId"
        private val DEFAULT_LENGTH = Duration.ofHours(1)

        val DEFINITION = ToolDefinition(
            id = ToolId("add_calendar_event"),
            description = "Adds an event to the user's calendar on this phone (ISO-8601 times, in the user's time zone " +
                "unless an offset is given). Without an end it lasts an hour. Check for clashes with calendar_events first " +
                "when it matters.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$TITLE":{"type":"string","description":"What it is"},""" +
                    """"$START":{"type":"string","description":"e.g. 2026-10-30T16:00"},""" +
                    """"$END":{"type":"string","description":"e.g. 2026-10-30T17:00"},""" +
                    """"$LOCATION":{"type":"string"},""" +
                    """"$NOTES":{"type":"string"},""" +
                    """"$CALENDAR":{"type":"string","description":"A calendar's name, when the user has several; leave out for their main one"}""" +
                    """},"required":["$TITLE","$START"]}""",
            ).jsonObject,
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = setOf(AndroidCapabilities.CALENDAR, AndroidCapabilities.CALENDAR_WRITE),
            consentTextKey = "consent_add_calendar_event",
            outputProvenance = Provenance.Trusted,
        )
    }
}
