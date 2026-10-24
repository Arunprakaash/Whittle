package app.whittle.tools.presentation

import app.whittle.core.common.getOrElse
import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Where and when the user is, as time words need it: now in their zone, their language and their clock. */
class UserClock(
    val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    val locale: () -> Locale = { Locale.getDefault() },
    val use24Hour: () -> Boolean = { true },
)

/**
 * Writes an ISO-8601 time the way a person says it: "today at 10:00", "tomorrow at 6:30 AM", "Friday at 16:00",
 * "Sat 31 Oct at 9:00". A time with an offset is shown in the user's zone; a bare time is just the time. Null when
 * [text] isn't a time.
 */
class PlainTime(private val words: TimeWords, private val clock: UserClock) {
    fun format(text: String): String? {
        val value = text.trim()
        parse(value, ::offsetDateTime)?.let { return dated(it.toLocalDate(), it.toLocalTime()) }
        parse(value, LocalDateTime::parse)?.let { return dated(it.toLocalDate(), it.toLocalTime()) }
        parse(value, LocalDate::parse)?.let { return dated(it, time = null) }
        parse(value, LocalTime::parse)?.let { return time(it) }
        return null
    }

    private fun offsetDateTime(text: String): LocalDateTime =
        OffsetDateTime.parse(text).atZoneSameInstant(clock.now().zone).toLocalDateTime()

    private fun dated(date: LocalDate, time: LocalTime?): String {
        val today = clock.now().toLocalDate()
        val phrases = if (time == null) words.date else words.dateTime
        val phrase = when {
            date == today -> phrases.today
            date == today.plusDays(1) -> phrases.tomorrow
            date.isAfter(today) && date.isBefore(today.plusDays(WEEK_DAYS)) -> phrases.week
            else -> phrases.later
        }
        val locale = clock.locale()
        val variables = buildMap {
            put(WEEKDAY, date.format(DateTimeFormatter.ofPattern(words.weekdayPattern, locale)))
            put(DATE, date.format(DateTimeFormatter.ofPattern(words.datePattern, locale)))
            time?.let { put(TIME, time(it)) }
        }
        return PromptTemplate(TEMPLATE_NAME, phrase).render(variables).getOrElse { date.toString() }
    }

    private fun time(time: LocalTime): String {
        val pattern = if (clock.use24Hour()) words.time24Pattern else words.time12Pattern
        return time.format(DateTimeFormatter.ofPattern(pattern, clock.locale()))
    }

    private fun <T> parse(text: String, parser: (String) -> T): T? = try {
        parser(text)
    } catch (exception: DateTimeParseException) {
        null
    }

    private companion object {
        /** Days after today that still read as a weekday rather than a date. */
        const val WEEK_DAYS = 7L
        const val TIME = "time"
        const val WEEKDAY = "weekday"
        const val DATE = "date"
        val TEMPLATE_NAME = ConfigName("tool-presentation.json")
    }
}
