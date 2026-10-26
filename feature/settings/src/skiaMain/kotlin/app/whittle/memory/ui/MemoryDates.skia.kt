package app.whittle.memory.ui

import androidx.compose.runtime.Composable
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.memory_yesterday
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

// A computer has no shared formatter for every target, so these are written out: "14:05", "Tue", "26 Oct 2026".
@Composable
internal actual fun savedWhen(millis: Long, nowMillis: Long): String {
    val at = local(millis)
    val today = local(nowMillis).date
    return when {
        at.date == today -> TIME.format(at)
        at.date == today.minus(DatePeriod(days = 1)) -> stringResource(Res.string.memory_yesterday)
        at.date > today.minus(DatePeriod(days = DAYS_SHOWN_BY_NAME.toInt())) -> WEEKDAY.format(at.date)
        else -> DATE.format(at.date)
    }
}

internal actual fun savedOn(millis: Long): String = local(millis).let { "${DATE.format(it.date)}, ${TIME.format(it)}" }

private fun local(millis: Long): LocalDateTime = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())

private val TIME = LocalDateTime.Format { hour(); char(':'); minute() }
private val WEEKDAY = LocalDate.Format { dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED) }
private val DATE = LocalDate.Format { day(); char(' '); monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); year() }
