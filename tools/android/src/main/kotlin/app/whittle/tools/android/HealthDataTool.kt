package app.whittle.tools.android

import app.whittle.core.common.EpochClock
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How far [HealthDataTool] reads: [defaultDays] when no start is given, at most [maxDays], and hourly rows for [maxHourlyDays]. */
data class HealthLimits(val defaultDays: Int, val maxDays: Int, val maxHourlyDays: Int) {
    init {
        require(defaultDays in 1..maxDays && maxHourlyDays in 1..maxDays) { "health day limits must be positive, with the default within the maximum" }
    }
}

/**
 * Reads one kind of the user's health data from Health Connect, per day or per hour. Only numbers, times and workout
 * types come back, never text another app wrote, so the output is trusted and can't steer the run.
 */
class HealthDataTool(private val health: HealthSource, private val clock: EpochClock, private val limits: HealthLimits) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val kind = HealthKind.of(arguments.text(KIND)) ?: return ToolJson.error("'$KIND' must be one of: ${HealthKind.entries.joinToString { it.id }}")
        val byHour = when (arguments.text(BY).ifEmpty { DAY }) {
            DAY -> false
            HOUR -> true
            else -> return ToolJson.error("'$BY' must be '$DAY' or '$HOUR'")
        }
        when (health.availability()) {
            HealthAvailability.UNSUPPORTED -> return ToolJson.error("Health Connect isn't available on this phone's version of Android, so Whittle can't read health data here.")
            HealthAvailability.NEEDS_APP -> return ToolJson.missing(AndroidCapabilities.HEALTH)
            HealthAvailability.READY -> Unit
        }
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val to = parseTime(arguments.text(TO), zone) ?: now
        val from = parseTime(arguments.text(FROM), zone)
            ?: LocalDate.ofInstant(to, zone).minusDays(limits.defaultDays - 1L).atStartOfDay(zone).toInstant()
        if (!to.isAfter(from)) return ToolJson.error("'$TO' must be after '$FROM'")
        val maxDays = if (byHour) limits.maxHourlyDays else limits.maxDays
        if (Duration.between(from, to) > Duration.ofDays(maxDays.toLong())) {
            return ToolJson.error("Ask for at most $maxDays days${if (byHour) " by hour; use '$DAY' for longer" else ""}.")
        }
        return try {
            if (kind.permission !in health.granted()) return ToolJson.missing(AndroidCapabilities.HEALTH)
            val rows = health.read(kind, from, to, byHour, zone)
            val olderThanAllowed = from.isBefore(now.minus(Duration.ofDays(PAST_DATA_DAYS))) && !health.pastDataAllowed()
            ToolJson.success(
                PROVENANCE,
                buildJsonObject {
                    put(KIND, kind.id)
                    put(TIME_ZONE, zone.id)
                    put(FROM, local(from, zone))
                    put(TO, local(to, zone))
                    put(BY, if (byHour) HOUR else DAY)
                    put(ROWS, rows)
                    if (olderThanAllowed) put(NOTE, "The user hasn't let Whittle read data older than the $PAST_DATA_DAYS days before Health was allowed, so earlier days may be missing.")
                },
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SecurityException) {
            // Revoked meanwhile, or a read while closed without the background permission.
            ToolJson.error("Health Connect refused the read: ${exception.message.orEmpty()}")
        } catch (exception: Exception) {
            ToolJson.error("Health Connect couldn't be read: ${exception.message.orEmpty()}")
        }
    }

    companion object {
        private const val KIND = "kind"
        private const val FROM = "from"
        private const val TO = "to"
        private const val BY = "by"
        private const val DAY = "day"
        private const val HOUR = "hour"
        private const val TIME_ZONE = "timeZone"
        private const val ROWS = "rows"
        private const val NOTE = "note"

        /** Without the past-data permission, Health Connect gives a new app only this many days before it was allowed. */
        private const val PAST_DATA_DAYS = 30L

        /** Numbers and times only; see the class comment. */
        private val PROVENANCE = Provenance.Trusted

        val DEFINITION = ToolDefinition(
            id = ToolId("health_data"),
            description = "Reads the user's health data from Health Connect (what their watch, band and fitness apps " +
                "recorded): one kind per call, per day or per hour, between two times (ISO-8601 dates or date-times in the " +
                "user's time zone). Defaults to the last 7 days. Read-only.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","required":["$KIND"],"properties":{""" +
                    """"$KIND":{"type":"string","enum":[${HealthKind.entries.joinToString { "\"${it.id}\"" }}]},""" +
                    """"$FROM":{"type":"string","description":"Start, e.g. 2026-10-01"},""" +
                    """"$TO":{"type":"string","description":"End, exclusive; defaults to now"},""" +
                    """"$BY":{"type":"string","enum":["$DAY","$HOUR"],"description":"Rows per day (default) or per hour; workouts, sleep and weight come back one row each"}}}""",
            ).jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = setOf(AndroidCapabilities.HEALTH),
            consentTextKey = "consent_health_data",
            outputProvenance = PROVENANCE,
            exposesPersonalData = true,
        )
    }
}
