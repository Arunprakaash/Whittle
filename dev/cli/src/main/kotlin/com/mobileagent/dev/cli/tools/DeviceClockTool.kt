package com.mobileagent.dev.cli.tools

import com.mobileagent.core.common.EpochClock
import com.mobileagent.core.model.Provenance
import com.mobileagent.tools.api.ExecutionSite
import com.mobileagent.tools.api.RiskTier
import com.mobileagent.tools.api.Tool
import com.mobileagent.tools.api.ToolDefinition
import com.mobileagent.tools.api.ToolExecutionContext
import com.mobileagent.tools.api.ToolId
import com.mobileagent.tools.api.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sample read-only tool: current date and time, optionally in a given IANA time zone. */
class DeviceClockTool(private val clock: EpochClock) : Tool {
    override val definition = ToolDefinition(
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
}
