package app.whittle.llm.responses

import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.Provenance
import app.whittle.core.model.Role
import app.whittle.core.model.ToolCallId
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ReasoningEffort
import app.whittle.llm.api.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponsesRequestMapperTest {
    private val mapper = ResponsesRequestMapper()
    private val reasoningItem = JsonObject(
        mapOf("type" to JsonPrimitive("reasoning"), "id" to JsonPrimitive("rs_1"), "encrypted_content" to JsonPrimitive("ENC")),
    )

    @Test
    fun `conversation round-trips reasoning, calls and outputs in order`() {
        val body = mapper.map(request(ReasoningEffort.HIGH), responsesProvider())
        val input = body.getValue("input").jsonArray.map { it.jsonObject }

        assertEquals(listOf("message", "reasoning", "message", "function_call", "function_call_output"), input.map { it.type() })
        assertEquals("ENC", input[1].getValue("encrypted_content").jsonPrimitive.content)
        assertEquals("call_1", input[3].getValue("call_id").jsonPrimitive.content)
        assertEquals("call_1", input[4].getValue("call_id").jsonPrimitive.content)
        assertEquals("output_text", input[2].getValue("content").jsonArray.single().jsonObject.type())
    }

    @Test
    fun `request is stateless and carries effort, token cap and tools`() {
        val body = mapper.map(request(ReasoningEffort.HIGH), responsesProvider())

        assertEquals(false, body.getValue("store").jsonPrimitive.boolean)
        assertEquals("reasoning.encrypted_content", body.getValue("include").jsonArray.single().jsonPrimitive.content)
        assertEquals("high", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals(512, body.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals("System", body.getValue("instructions").jsonPrimitive.content)
        assertEquals("function", body.getValue("tools").jsonArray.single().jsonObject.type())
    }

    @Test
    fun `no effort is sent for a model without reasoning`() {
        val body = mapper.map(request(ReasoningEffort.HIGH).copy(model = reasoningModel(reasoning = false)), responsesProvider())
        assertNull(body["reasoning"])
    }

    @Test
    fun `reasoning state from another format is not resent`() {
        val foreign = Message(Role.ASSISTANT, listOf(ContentBlock.Reasoning(text = null, providerState = reasoningItem)))
        val body = mapper.map(request(ReasoningEffort.LOW).copy(messages = listOf(foreign)), responsesProvider())
        assertEquals(emptyList(), body.getValue("input").jsonArray.toList())
    }

    private fun request(effort: ReasoningEffort) = ModelRequest(
        model = reasoningModel(),
        systemPrompt = "System",
        messages = listOf(
            Message.userText("What time is it in Tokyo?"),
            Message(
                Role.ASSISTANT,
                listOf(
                    ContentBlock.Reasoning(text = null, providerState = ReasoningStateCodec.wrap(reasoningItem)),
                    ContentBlock.Text("Checking."),
                    ContentBlock.ToolCall(ToolCallId("call_1"), "device_clock_now", """{"timeZone":"Asia/Tokyo"}"""),
                ),
            ),
            Message(Role.USER, listOf(ContentBlock.ToolResult(ToolCallId("call_1"), "05:40", isError = false, provenance = Provenance.Trusted))),
        ),
        tools = listOf(ToolSpec("device_clock_now", "Clock", JsonObject(emptyMap()))),
        maxOutputTokens = 512,
        reasoningEffort = effort,
    )

    private fun JsonObject.type(): String = getValue("type").jsonPrimitive.content
}
