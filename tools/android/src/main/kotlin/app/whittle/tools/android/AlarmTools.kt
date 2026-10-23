package app.whittle.tools.android

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.Calendar

/**
 * Sets an alarm in the phone's clock app without opening it. The alarm is the clock app's, so the user changes or
 * removes it there; Android grants the permission at install.
 */
class SetAlarmTool(private val context: Context) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val time = try {
            LocalTime.parse(arguments.text(TIME))
        } catch (exception: DateTimeParseException) {
            return ToolJson.error("time must be HH:mm, 24-hour")
        }
        val days = (arguments[DAYS] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty().uppercase() }
        val weekdays = days.map { day -> DAY_NAMES[day] ?: return ToolJson.error("days must be MON, TUE, WED, THU, FRI, SAT or SUN") }
        val label = arguments.text(LABEL)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply {
                if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                if (weekdays.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(weekdays.distinct().map(::calendarDay)))
            }
        if (!this.context.startHandOff(intent)) return ToolJson.error("no clock app on this phone sets alarms")
        return ToolJson.success(
            Provenance.Trusted,
            buildJsonObject {
                put(SET, true)
                put(TIME, time.toString())
                // The clock rings at the next such time, which the user may not mean by "tomorrow" just after midnight.
                put(RINGS_AT, AlarmTimes.nextRing(LocalDateTime.now(), time, weekdays.toSet()).toString())
                if (weekdays.isNotEmpty()) put(DAYS, JsonArray(weekdays.distinct().map { JsonPrimitive(it.name.take(3)) }))
                put(NOTE, "The alarm is in the clock app; the user changes or removes it there.")
            },
        )
    }

    /** java.util.Calendar numbers days from Sunday, which is what the clock app reads. */
    private fun calendarDay(day: DayOfWeek): Int = if (day == DayOfWeek.SUNDAY) Calendar.SUNDAY else day.value + 1

    companion object {
        private const val TIME = "time"
        private const val LABEL = "label"
        private const val DAYS = "days"
        private const val SET = "set"
        private const val NOTE = "note"
        private const val RINGS_AT = "ringsAt"
        private val DAY_NAMES = DayOfWeek.entries.associateBy { it.name.take(3) }

        val DEFINITION = ToolDefinition(
            id = ToolId("set_alarm"),
            description = "Sets an alarm in the phone's clock app, without opening it. For a one-off alarm leave days out; " +
                "it rings at the next occurrence of the time (ringsAt says when), so it can't be set for a later date. " +
                "To change or remove an alarm, the user uses the clock app.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$TIME":{"type":"string","description":"24-hour time, e.g. 06:30"},""" +
                    """"$LABEL":{"type":"string","description":"What it's for, shown when it rings"},""" +
                    """"$DAYS":{"type":"array","items":{"type":"string","enum":["MON","TUE","WED","THU","FRI","SAT","SUN"]},""" +
                    """"description":"Days it repeats on; leave out for once"}},"required":["$TIME"]}""",
            ).jsonObject,
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_set_alarm",
            outputProvenance = Provenance.Trusted,
        )
    }
}

/** When an alarm set now rings; pure, so it's tested on the JVM. */
internal object AlarmTimes {
    /** The first [time] after [now] on one of [days], or on any day when there are none. */
    fun nextRing(now: LocalDateTime, time: LocalTime, days: Set<DayOfWeek>): LocalDateTime {
        var candidate = now.toLocalDate().atTime(time)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        if (days.isEmpty()) return candidate
        while (candidate.dayOfWeek !in days) candidate = candidate.plusDays(1)
        return candidate
    }
}

/** Starts a countdown timer in the phone's clock app, without opening it. */
class SetTimerTool(private val context: Context) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val seconds = (arguments[SECONDS] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        if (seconds == null || seconds !in 1..MAX_SECONDS) return ToolJson.error("seconds must be between 1 and $MAX_SECONDS")
        val label = arguments.text(LABEL)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
        if (!this.context.startHandOff(intent)) return ToolJson.error("no clock app on this phone sets timers")
        return ToolJson.success(Provenance.Trusted, buildJsonObject { put(STARTED, true); put(SECONDS, seconds) })
    }

    companion object {
        private const val SECONDS = "seconds"
        private const val LABEL = "label"
        private const val STARTED = "started"

        /** The clock app's own limit: a timer of up to 24 hours. */
        private const val MAX_SECONDS = 24 * 60 * 60

        val DEFINITION = ToolDefinition(
            id = ToolId("set_timer"),
            description = "Starts a countdown timer in the phone's clock app, without opening it.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$SECONDS":{"type":"integer","description":"Length in seconds, up to 86400"},""" +
                    """"$LABEL":{"type":"string","description":"What it's for"}},"required":["$SECONDS"]}""",
            ).jsonObject,
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_set_timer",
            outputProvenance = Provenance.Trusted,
        )
    }
}
