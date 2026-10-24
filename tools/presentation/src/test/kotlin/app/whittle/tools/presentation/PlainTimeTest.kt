package app.whittle.tools.presentation

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlainTimeTest {
    private val words = TimeWords(
        dateTime = DayPhrases("today at {{time}}", "tomorrow at {{time}}", "{{weekday}} at {{time}}", "{{date}} at {{time}}"),
        date = DayPhrases("today", "tomorrow", "{{weekday}}", "{{date}}"),
        weekdayPattern = "EEEE",
        datePattern = "EEE d MMM",
        time12Pattern = "h:mm a",
        time24Pattern = "HH:mm",
    )

    // Saturday noon in Bengaluru.
    private val now = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    private fun plain(use24Hour: Boolean = true) = PlainTime(words, UserClock(now = { now }, locale = { Locale.US }, use24Hour = { use24Hour }))

    @Test
    fun `date-times read as today, tomorrow, a weekday or a date`() {
        assertEquals("today at 18:30", plain().format("2026-10-24T18:30"))
        assertEquals("tomorrow at 10:00", plain().format("2026-10-25T10:00"))
        assertEquals("Wednesday at 09:00", plain().format("2026-10-28T09:00"))
        assertEquals("Thu 5 Nov at 09:00", plain().format("2026-11-05T09:00"))
        assertEquals("Fri 23 Oct at 09:00", plain().format("2026-10-23T09:00"))
    }

    @Test
    fun `a 12-hour phone gets a 12-hour time`() {
        assertEquals("tomorrow at 10:00 AM", plain(use24Hour = false).format("2026-10-25T10:00"))
        assertEquals("6:30 AM", plain(use24Hour = false).format("06:30"))
    }

    @Test
    fun `a time with an offset shows in the user's zone`() {
        assertEquals("tomorrow at 10:00", plain().format("2026-10-25T04:30:00Z"))
    }

    @Test
    fun `dates and bare times read on their own`() {
        assertEquals("tomorrow", plain().format("2026-10-25"))
        assertEquals("06:30", plain().format("06:30"))
    }

    @Test
    fun `anything else isn't a time`() {
        assertNull(plain().format("call the bank"))
        assertNull(plain().format("2026-13-40T10:00"))
    }

    @Test
    fun `cards read times in plain words and keep other text as written`() {
        val presenter = ToolPresenter(
            ToolPresentationConfig(
                schemaVersion = ToolPresentationConfig.SCHEMA_VERSION,
                fallback = ToolPresentation("build", "Working", "Done"),
                tools = mapOf("remind_me" to ToolPresentation("notifications", "Setting a reminder", "Reminder {{at_when}}")),
                times = words,
            ),
            UserClock(now = { now }, locale = { Locale.US }, use24Hour = { true }),
        )
        assertEquals("Reminder tomorrow at 10:00", presenter.step("remind_me", arguments("2026-10-25T10:00")).done)
        assertEquals("Reminder next Tuesday", presenter.step("remind_me", arguments("next Tuesday")).done)
    }

    private fun arguments(at: String) = kotlinx.serialization.json.JsonObject(mapOf("at" to kotlinx.serialization.json.JsonPrimitive(at)))
}
