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
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleTaskToolTest {
    private val messages = assertIs<Outcome.Success<AgentMessages>>(
        ConfigLoader(BundledConfigSource()).load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION),
    ).value
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"), WorkspaceId("chat-1"))
    private val chatProject = mapOf(WorkspaceId("chat-1") to "notes")
    private val folders = setOf("notes", "brief")

    private fun parse(json: String) = ScheduleTaskTool.parse(Json.parseToJsonElement(json).jsonObject)

    private fun TestScope.tool(): Pair<Automations, ScheduleTaskTool> {
        val automations = Automations(MemoryBackend(), RecordingAlarms(), testAutomationsConfig, EpochClock { 0 }, IdGenerator { "a" }, { ZoneId.of("UTC") }, this)
        return automations to ScheduleTaskTool(automations, messages, chatProject::get, folders::contains)
    }

    private suspend fun ScheduleTaskTool.call(json: String) = execute(Json.parseToJsonElement(json).jsonObject, context)

    @Test
    fun `an automation takes the chat's project unless another is given`() = runTest {
        val (automations, tool) = tool()
        val reply = tool.call("""{"name":"Brief","prompt":"Brief me","time":"07:00"}""")
        assertFalse(reply.isError, reply.content)
        assertEquals("notes", automations.all.value.single().project)
        assertTrue("notes" in reply.content, reply.content)
    }

    @Test
    fun `an empty project means the whole workspace`() = runTest {
        val (automations, tool) = tool()
        tool.call("""{"name":"Brief","prompt":"Brief me","time":"07:00","project":""}""")
        assertNull(automations.all.value.single().project)
    }

    @Test
    fun `a project that isn't in the workspace is refused`() = runTest {
        val (automations, tool) = tool()
        val reply = tool.call("""{"name":"Brief","prompt":"Brief me","time":"07:00","project":"travel"}""")
        assertTrue(reply.isError)
        assertEquals(messages.automationProjectMissing("travel"), reply.content)
        assertTrue(automations.all.value.isEmpty())
    }

    @Test
    fun `explains a time it can't read with the configured message`() = runTest {
        val (_, tool) = tool()
        assertEquals(messages.automationTimeInvalid, tool.call("""{"name":"a","prompt":"b","time":"7am"}""").content)
    }

    @Test
    fun `reads the name, prompt, time and days`() {
        val request = assertIs<ScheduleTaskTool.Parsed.Request>(
            parse("""{"name":" Brief ","prompt":"Brief me","time":"07:30","days":["mon","FRI"]}"""),
        )
        assertEquals("Brief", request.name)
        assertEquals(LocalTime.of(7, 30), request.schedule.time)
        assertEquals(setOf(1, 5), request.schedule.days)
    }

    @Test
    fun `no days, or an empty list, means every day`() {
        assertEquals(Schedule.EVERY_DAY, assertIs<ScheduleTaskTool.Parsed.Request>(parse("""{"name":"a","prompt":"b","time":"07:00"}""")).schedule.days)
        assertEquals(Schedule.EVERY_DAY, assertIs<ScheduleTaskTool.Parsed.Request>(parse("""{"name":"a","prompt":"b","time":"07:00","days":[]}""")).schedule.days)
    }

    @Test
    fun `explains a time or day it can't read`() {
        assertEquals(ScheduleTaskTool.Parsed.Invalid.TIME, parse("""{"name":"a","prompt":"b","time":"7am"}"""))
        assertEquals(ScheduleTaskTool.Parsed.Invalid.DAYS, parse("""{"name":"a","prompt":"b","time":"07:00","days":["someday"]}"""))
        assertEquals(ScheduleTaskTool.Parsed.Invalid.DAYS, parse("""{"name":"a","prompt":"b","time":"07:00","days":"mon"}"""))
    }
}
