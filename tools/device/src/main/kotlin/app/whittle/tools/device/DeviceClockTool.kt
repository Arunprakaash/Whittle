package app.whittle.tools.device

import app.whittle.core.common.EpochClock
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Read-only tool: the current date and time, optionally in a given IANA time zone. */
class DeviceClockTool(private val clock: EpochClock) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val zoneName = arguments["timeZone"]?.jsonPrimitive?.contentOrNull
        val zone = try {
            zoneName?.let(ZoneId::of) ?: ZoneId.systemDefault()
        } catch (exception: DateTimeException) {
            return ToolOutput("Unknown time zone '$zoneName'", isError = true, provenance = Provenance.Trusted)
        }
        val now = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone)
        return ToolOutput(DateTimeFormatter.RFC_1123_DATE_TIME.format(now), isError = false, provenance = Provenance.Trusted)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            id = ToolId("device_clock_now"),
            description = "Returns the current date and time. Optionally pass an IANA time zone such as Asia/Kolkata.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"timeZone":{"type":"string","description":"IANA time zone id"}}}""",
            ).jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_device_clock_now",
            outputProvenance = Provenance.Trusted,
        )
    }
}
