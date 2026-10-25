package app.whittle.phone

import app.whittle.core.model.Provenance
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.CallPreview
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchedToolTest {
    private val alarm = object : Tool {
        override val definition = ToolDefinition(
            id = ToolId("set_alarm"), description = "", inputSchema = JsonObject(emptyMap()), riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE, requiredCapabilities = emptySet(), consentTextKey = "", outputProvenance = Provenance.Trusted,
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext) = ToolOutput("set", isError = false, provenance = Provenance.Trusted)

        override suspend fun preview(arguments: JsonObject) = CallPreview(mapOf("time" to "7:00"), approved = listOf("7:00"))
    }
    private val config = PhoneActionsConfig(
        schemaVersion = 1,
        maps = PhoneActionsConfig.Maps("geo:0,0?q={{query}}", "https://maps.example/?d={{destination}}&m={{mode}}"),
        reminders = PhoneActionsConfig.Reminders("r.bin", 1, 1),
        share = PhoneActionsConfig.Share("share", 1),
        health = PhoneActionsConfig.Health("health.app", "market://details?id={{package}}", defaultDays = 7, maxDays = 30, maxHourlyDays = 2, maxRecords = 10),
        storage = PhoneActionsConfig.Storage(1, 1, 1, 1, 1, 1, "kept", listOf("image", "video", "audio", "document", "apk", "archive").associateWith { emptyList<String>() }),
        switches = listOf(PhoneActionsConfig.Switch("alarms", "alarm", "Alarms and timers", emptyList(), listOf("set_alarm"))),
        offMessage = "{{feature}} is off",
    )
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"))

    @Test
    fun `a switched-off tool says so and does nothing, and runs again once it's on`() = runTest {
        var on = false
        val tool = switched(setOf(alarm), config) { on }.single()
        assertEquals(alarm.definition, tool.definition)
        val refused = tool.execute(JsonObject(emptyMap()), context)
        assertTrue(refused.isError && "Alarms and timers is off" in refused.content)
        on = true
        assertFalse(tool.execute(JsonObject(emptyMap()), context).isError)
    }

    @Test
    fun `a switched tool keeps its own preview`() = runTest {
        val tool = switched(setOf(alarm), config) { true }.single()
        assertEquals(alarm.preview(JsonObject(emptyMap())), tool.preview(JsonObject(emptyMap())))
    }

    @Test
    fun `a switched-off tool has no preview`() = runTest {
        var on = false
        val tool = switched(setOf(alarm), config) { on }.single()
        assertNull(tool.preview(JsonObject(emptyMap())))
        on = true
        assertEquals(alarm.preview(JsonObject(emptyMap())), tool.preview(JsonObject(emptyMap())))
    }
}
