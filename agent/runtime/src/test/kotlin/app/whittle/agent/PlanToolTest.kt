package app.whittle.agent

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanToolTest {
    private val tool = PlanTool(maxItems = 3)
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"))

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a valid plan is saved and parses back`() = runTest {
        val arguments = args("""{"items":[{"content":"Find sources","status":"completed"},{"content":"Write it","status":"in_progress"}]}""")
        val output = tool.execute(arguments, context)
        assertEquals(false, output.isError)
        assertEquals("Plan saved: 1 of 2 steps done.", output.content)
        assertEquals(listOf(PlanStatus.COMPLETED, PlanStatus.IN_PROGRESS), PlanTool.parse(arguments)?.map { it.status })
    }

    @Test
    fun `plans that can't be shown are refused`() = runTest {
        assertEquals(true, tool.execute(args("""{"items":[]}"""), context).isError)
        assertEquals(true, tool.execute(args("""{"items":[{"content":"a","status":"pending"},{"content":"b","status":"pending"},{"content":"c","status":"pending"},{"content":"d","status":"pending"}]}"""), context).isError)
        assertEquals(true, tool.execute(args("""{"items":[{"content":"a","status":"in_progress"},{"content":"b","status":"in_progress"}]}"""), context).isError)
        assertEquals(true, tool.execute(args("""{"items":[{"content":"a","status":"started"}]}"""), context).isError)
    }
}
