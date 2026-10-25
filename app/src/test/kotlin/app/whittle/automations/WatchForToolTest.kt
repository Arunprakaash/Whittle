package app.whittle.automations

import app.whittle.agent.AgentMessages
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchForToolTest {
    private val messages = assertIs<Outcome.Success<AgentMessages>>(
        ConfigLoader(BundledConfigSource()).load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION),
    ).value
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"), WorkspaceId("chat-1"))
    private val installed = mutableListOf(
        LaunchableApp("com.whatsapp", "WhatsApp"),
        LaunchableApp("com.swiggy", "Swiggy"),
        LaunchableApp("org.one.notes", "Notes"),
        LaunchableApp("org.two.notes", "Notes"),
    )

    private fun TestScope.tool(): Pair<Automations, WatchForTool> {
        var count = 0
        val automations = Automations(MemoryBackend(), RecordingAlarms(), testAutomationsConfig, EpochClock { 0 }, IdGenerator { "a${count++}" }, { ZoneId.of("UTC") }, this)
        return automations to WatchForTool(automations, messages, { null }, { true }, { installed })
    }

    private suspend fun WatchForTool.call(json: String) = execute(Json.parseToJsonElement(json).jsonObject, context)

    @Test
    fun `a text event saves an SMS trigger with the default tools`() = runTest {
        val (automations, tool) = tool()
        val reply = tool.call("""{"name":"Bank","prompt":"Log it","event":"text","from":"HDFCBK, ICICI","words":"debited,credited"}""")
        assertFalse(reply.isError, reply.content)
        val saved = automations.all.value.single()
        assertNull(saved.schedule)
        assertEquals(Trigger.Sms(listOf("HDFCBK", "ICICI"), listOf("debited", "credited")), saved.trigger)
        assertEquals(setOf("web_search", "web_fetch"), saved.tools)
        // It says what runs on its own, where to allow more, and which permission is still needed.
        assertTrue("web" in reply.content, reply.content)
        assertTrue("Settings › Automations" in reply.content, reply.content)
        assertTrue(messages.automationPermissions.getValue("SMS") in reply.content, reply.content)
    }

    @Test
    fun `a place is sent to Settings`() = runTest {
        val (automations, tool) = tool()
        val reply = tool.call("""{"name":"Home","prompt":"Remind me","event":"place_arrive","from":"Home"}""")
        assertTrue(reply.isError)
        assertEquals(messages.automationPlaceInSettings, reply.content)
        assertTrue(automations.all.value.isEmpty())
    }

    @Test
    fun `an app that isn't on the phone is refused, and a name two apps share lists them`() = runTest {
        val (automations, tool) = tool()
        val missing = tool.call("""{"name":"Chat","prompt":"Tell me","event":"notification","from":"Telegram"}""")
        assertTrue(missing.isError)
        assertEquals(messages.automationAppNotFound("Telegram"), missing.content)
        val twice = tool.call("""{"name":"Chat","prompt":"Tell me","event":"notification","from":"notes"}""")
        assertTrue(twice.isError)
        assertTrue("org.one.notes" in twice.content && "org.two.notes" in twice.content, twice.content)
        assertTrue(automations.all.value.isEmpty())
    }

    @Test
    fun `an app name matches ignoring case and saves its package`() = runTest {
        val (automations, tool) = tool()
        tool.call("""{"name":"Food","prompt":"Tell me","event":"notification","from":"swiggy","words":"arriving"}""")
        assertEquals(Trigger.Notification("com.swiggy", "Swiggy", listOf("arriving")), automations.all.value.single().trigger)
    }

    @Test
    fun `an interval below the floor is refused`() = runTest {
        val (automations, tool) = tool()
        val reply = tool.call("""{"name":"Check","prompt":"Look","event":"every","minutes":5}""")
        assertTrue(reply.isError)
        assertEquals(messages.automationIntervalTooShort(testEventsConfig.minIntervalMinutes), reply.content)
        assertTrue(automations.all.value.isEmpty())
        assertEquals(messages.automationMinutesRequired, tool.call("""{"name":"Check","prompt":"Look","event":"every"}""").content)
    }

    @Test
    fun `charging and Wi-Fi events pick their side`() = runTest {
        val (automations, tool) = tool()
        tool.call("""{"name":"Plug","prompt":"Summarise","event":"charging_start"}""")
        tool.call("""{"name":"Away","prompt":"Note","event":"wifi_leave","from":"Home WiFi"}""")
        assertEquals(
            listOf(Trigger.Charging(Edge.START), Trigger.Wifi("Home WiFi", Edge.END)),
            automations.all.value.map { it.trigger },
        )
    }
}
