package app.whittle.llm.anthropic

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Secret
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
import app.whittle.core.model.Role
import app.whittle.core.model.ToolCallId
import app.whittle.core.model.Provenance
import app.whittle.llm.api.ApiFormat
import app.whittle.llm.api.AssistantTurnAssembler
import app.whittle.llm.api.HostedTool
import app.whittle.llm.api.HostedToolSupport
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ProviderAuth
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.api.ReasoningEffort
import app.whittle.llm.api.RequestOverride
import app.whittle.llm.api.StopReason
import app.whittle.llm.api.ToolSpec
import app.whittle.llm.api.TrustTier
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicMessagesAdapterTest {
    private val server = MockWebServer()
    private val adapter = AnthropicMessagesAdapter()

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `streams thinking and text, then the tool call, usage and the turn to send back`() = runTest {
        server.enqueue(sse(TOOL_TURN))
        val events = adapter.stream(request(), provider(), Secret("sk-test")).toList()

        assertEquals(listOf("Checking the clock."), events.filterIsInstance<ModelEvent.ReasoningDelta>().map { it.text })
        assertEquals("Let me look.", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        val call = events.filterIsInstance<ModelEvent.ToolCallCompleted>().single().toolCall
        assertEquals(ToolCallId("toolu_1"), call.id)
        assertEquals("device_clock_now", call.toolName)
        assertEquals(JsonObject(mapOf("zone" to JsonPrimitive("UTC"))), Json.parseToJsonElement(call.argumentsJson))
        val usage = events.filterIsInstance<ModelEvent.Usage>().single().usage
        assertEquals(listOf(12L, 30L, 100L, 5L), listOf(usage.inputTokens, usage.outputTokens, usage.cacheReadTokens, usage.cacheWriteTokens))
        assertEquals(StopReason.TOOL_USE, events.filterIsInstance<ModelEvent.Completed>().single().stopReason)

        val sent = server.takeRequest()
        assertEquals("/v1/messages", sent.path)
        assertEquals("sk-test", sent.getHeader("x-api-key"))
        assertEquals("server-side-fallback-2026-07-01,thinking-binding-controls-2026-08-01", sent.getHeader("anthropic-beta"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body.string("model"))
        assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("ephemeral", body["system"]!!.jsonArray[0].jsonObject["cache_control"]!!.jsonObject.string("type"))
        assertEquals("ephemeral", body["cache_control"]!!.jsonObject.string("type"))
        assertEquals("adaptive", body["thinking"]!!.jsonObject.string("type"))
        assertEquals("summarized", body["thinking"]!!.jsonObject.string("display"))
        // Earlier thinking that no longer matches the conversation (a new date or memory in the system prompt) is dropped, not refused.
        assertEquals("drop_block", body["thinking"]!!.jsonObject["block_binding"]!!.jsonObject.string("prefix_mismatch_behavior"))
        assertEquals("high", body["output_config"]!!.jsonObject.string("effort"))
        assertEquals("default", body.string("fallbacks"))
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("device_clock_now", "web_search"), tools.map { it.string("name") })
        assertEquals("web_search_20260209", tools[1].string("type"))
        assertEquals("string", tools[0]["input_schema"]!!.jsonObject["properties"]!!.jsonObject["zone"]!!.jsonObject.string("type"))
    }

    @Test
    fun `a Claude turn goes back exactly as it came, thinking signature and all`() = runTest {
        server.enqueue(sse(TOOL_TURN))
        val assembler = AssistantTurnAssembler()
        adapter.stream(request(), provider(), Secret("sk-test")).toList().forEach(assembler::accept)
        server.takeRequest()

        server.enqueue(sse(TEXT_TURN))
        val history = listOf(
            Message(Role.USER, listOf(ContentBlock.Text("What time is it?"))),
            assembler.toMessage(),
            Message(Role.USER, listOf(ContentBlock.ToolResult(ToolCallId("toolu_1"), "12:00", isError = false, provenance = Provenance.Trusted))),
        )
        adapter.stream(request(messages = history), provider(), Secret("sk-test")).toList()
        val messages = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        val replayed = messages[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text", "tool_use"), replayed.map { it.string("type") })
        assertEquals("sig-1", replayed[0].string("signature"))
        assertEquals("Checking the clock.", replayed[0].string("thinking"))
        val result = messages[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("tool_result", "toolu_1", "12:00"), listOf(result.string("type"), result.string("tool_use_id"), result.string("content")))
    }

    @Test
    fun `an empty turn keeps nothing to send back, and is never sent back empty`() = runTest {
        server.enqueue(sse(START + end("refusal")))
        val events = adapter.stream(request(), provider(), Secret("sk-test")).toList()
        assertTrue(events.none { it is ModelEvent.ReasoningState }, "nothing to replay")
        assertEquals(StopReason.REFUSAL, events.filterIsInstance<ModelEvent.Completed>().single().stopReason)
        server.takeRequest()

        // A turn saved before this fix, with an empty replay state.
        server.enqueue(sse(TEXT_TURN))
        val empty = Message(Role.ASSISTANT, listOf(ContentBlock.Reasoning(text = null, providerState = AnthropicTurnCodec.wrap(JsonArray(emptyList())))))
        val history = listOf(Message(Role.USER, listOf(ContentBlock.Text("Hi"))), empty, Message(Role.USER, listOf(ContentBlock.Text("Still there?"))))
        adapter.stream(request(messages = history), provider(), Secret("sk-test")).toList()
        val messages = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertTrue(messages.none { it.jsonObject.string("role") == "assistant" && it.jsonObject["content"]!!.jsonArray.isEmpty() })
    }

    @Test
    fun `a turn from another model is rebuilt from its text and tool calls`() = runTest {
        server.enqueue(sse(TEXT_TURN))
        val other = Message(
            Role.ASSISTANT,
            listOf(
                ContentBlock.Reasoning(text = "thought", providerState = buildJsonObject { put("format", ApiFormat.OPENAI_RESPONSES.name) }),
                ContentBlock.Text("On it."),
                ContentBlock.ToolCall(ToolCallId("call_9"), "device_clock_now", "not json"),
            ),
        )
        val history = listOf(Message(Role.USER, listOf(ContentBlock.Text("Time?"))), other)
        adapter.stream(request(messages = history), provider(), Secret("sk-test")).toList()
        val rebuilt = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "tool_use"), rebuilt.map { it.jsonObject.string("type") })
        assertEquals(JsonObject(emptyMap()), rebuilt[1].jsonObject["input"])
    }

    @Test
    fun `a web search shows as a hosted step, and its sources are cited`() = runTest {
        server.enqueue(sse(SEARCH_TURN))
        val events = adapter.stream(request(), provider(), Secret("sk-test")).toList()
        assertEquals(ModelEvent.HostedToolStarted("srvtoolu_1", HostedTool.WEB_SEARCH), events.filterIsInstance<ModelEvent.HostedToolStarted>().single())
        val finished = events.filterIsInstance<ModelEvent.HostedToolFinished>().single()
        assertEquals("search", finished.action)
        assertEquals("whittle android", finished.arguments.string("query"))
        assertEquals(ModelEvent.SourceCited("https://example.com/whittle", "Whittle"), events.filterIsInstance<ModelEvent.SourceCited>().single())
        assertEquals(StopReason.END_TURN, events.filterIsInstance<ModelEvent.Completed>().single().stopReason)
    }

    @Test
    fun `after a fallback mid-turn only the new model's tool calls run, and the declined part isn't sent back`() = runTest {
        server.enqueue(sse(FALLBACK_TURN))
        val events = adapter.stream(request(), provider(), Secret("sk-test")).toList()
        assertEquals(listOf("toolu_new"), events.filterIsInstance<ModelEvent.ToolCallCompleted>().map { it.toolCall.id.value })
        val state = AnthropicTurnCodec.unwrap(events.filterIsInstance<ModelEvent.ReasoningState>().single().providerState)!!
        assertEquals(listOf("text", "text", "tool_use"), state.map { it.jsonObject.string("type") })
        assertEquals("toolu_new", state[2].jsonObject.string("id"))
    }

    @Test
    fun `a rejected key fails with its status, and a cut-off stream fails instead of completing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val rejected = adapter.stream(request(), provider(), Secret("bad")).toList().single()
        assertEquals(401, assertIs<HarnessError.Provider>(assertIs<ModelEvent.Failed>(rejected).error).statusCode)

        server.enqueue(sse(TOOL_TURN.substringBefore("event: message_delta")))
        val cut = adapter.stream(request(), provider(), Secret("sk-test")).toList()
        assertIs<HarnessError.Network>(assertIs<ModelEvent.Failed>(cut.last()).error)
        assertNull(cut.filterIsInstance<ModelEvent.Completed>().firstOrNull())
    }

    @Test
    fun `an error sent mid-stream fails with the status its type stands for, so it can be retried`() = runTest {
        for ((type, status) in listOf("overloaded_error" to 529, "rate_limit_error" to 429, "api_error" to 500)) {
            server.enqueue(sse(START + event("error", "{\"type\":\"error\",\"error\":{\"type\":\"$type\",\"message\":\"Busy\"}}")))
            val failed = adapter.stream(request(), provider(), Secret("sk-test")).toList().last()
            assertEquals(status, assertIs<HarnessError.Provider>(assertIs<ModelEvent.Failed>(failed).error).statusCode, type)
            server.takeRequest()
        }
    }

    @Test
    fun `a tool call cut off by the output limit neither runs nor goes back`() = runTest {
        server.enqueue(sse(TOOL_TURN.substringBefore("event: message_delta") + end("max_tokens")))
        val events = adapter.stream(request(), provider(), Secret("sk-test")).toList()
        assertTrue(events.none { it is ModelEvent.ToolCallCompleted }, "nothing to run")
        val state = AnthropicTurnCodec.unwrap(events.filterIsInstance<ModelEvent.ReasoningState>().single().providerState)!!
        assertEquals(listOf("thinking", "text"), state.map { it.jsonObject.string("type") }, "the thinking and text stay, the call goes")
        val assembler = AssistantTurnAssembler().apply { events.forEach(::accept) }
        assertTrue(assembler.toMessage().content.none { it is ContentBlock.ToolCall })
    }

    @Test
    fun `models without reasoning or caching get neither`() = runTest {
        server.enqueue(sse(TEXT_TURN))
        val plain = ModelCapabilities(toolCalling = true, parallelToolCalls = true, vision = true, promptCaching = false, reasoning = false)
        adapter.stream(request(capabilities = plain, effort = ReasoningEffort.NONE), provider(), Secret("sk-test")).toList()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(listOf("thinking", "output_config", "cache_control").none { it in body })
        assertEquals("text", body["system"]!!.jsonArray[0].jsonObject.string("type"))
        assertNull(body["system"]!!.jsonArray[0].jsonObject["cache_control"])
    }

    private fun request(
        messages: List<Message> = listOf(Message(Role.USER, listOf(ContentBlock.Text("What time is it?")))),
        capabilities: ModelCapabilities = ModelCapabilities(toolCalling = true, parallelToolCalls = true, vision = true, promptCaching = true, reasoning = true),
        effort: ReasoningEffort = ReasoningEffort.HIGH,
    ) = ModelRequest(
        model = ModelDescriptor(ModelId("claude-opus-5-5"), ProviderId("anthropic"), "Claude Opus 5.5", 1_000_000, capabilities, TrustTier.A),
        systemPrompt = "You are Whittle.",
        messages = messages,
        tools = listOf(ToolSpec("device_clock_now", "The time.", Json.parseToJsonElement("""{"type":"object","properties":{"zone":{"type":"string"}}}""").jsonObject)),
        maxOutputTokens = 64_000,
        reasoningEffort = effort,
        hostedTools = setOf(HostedTool.WEB_SEARCH),
    )

    private fun provider() = ProviderDescriptor(
        id = ProviderId("anthropic"),
        displayName = "Anthropic",
        apiFormat = ApiFormat.ANTHROPIC_MESSAGES,
        baseUrl = server.url("/").toString(),
        generationPath = "v1/messages",
        auth = ProviderAuth("x-api-key", ""),
        maxOutputTokensParameter = "max_tokens",
        defaultHeaders = mapOf("anthropic-version" to "2023-06-01", "anthropic-beta" to "server-side-fallback-2026-07-01,thinking-binding-controls-2026-08-01"),
        thinkingFields = buildJsonObject { put("block_binding", buildJsonObject { put("prefix_mismatch_behavior", "drop_block") }) },
        requestOverrides = listOf(RequestOverride("claude-opus-5-5", buildJsonObject { put("fallbacks", "default") })),
        hostedTools = listOf(HostedToolSupport(HostedTool.WEB_SEARCH, listOf("claude-opus-5*"))),
    )

    private fun sse(events: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private companion object {
        fun event(type: String, data: String) = "event: $type\ndata: $data\n\n"

        val START = event("message_start", """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":100,"cache_creation_input_tokens":5}}}""")

        fun end(stopReason: String) =
            event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},"usage":{"output_tokens":30}}""") +
                event("message_stop", """{"type":"message_stop"}""")

        val TOOL_TURN = START +
            event("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Checking the clock."}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            event("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Let me "}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"look."}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":1}""") +
            event("content_block_start", """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"device_clock_now","input":{}}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"zone\":"}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":" \"UTC\"}"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":2}""") +
            end("tool_use")

        val TEXT_TURN = START +
            event("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"It's noon."}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            end("end_turn")

        val SEARCH_TURN = START +
            event("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\": \"whittle android\"}"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            event("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://example.com/whittle","title":"Whittle","encrypted_content":"enc","page_age":null}]}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":1}""") +
            event("content_block_start", """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":"","citations":[]}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","cited_text":"Whittle runs on Android","url":"https://example.com/whittle","title":"Whittle","encrypted_index":"idx"}}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"Whittle runs on Android."}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":2}""") +
            end("end_turn")

        val FALLBACK_TURN = START +
            event("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Start of an answer"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            event("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_old","name":"device_clock_now","input":{}}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":1}""") +
            event("content_block_start", """{"type":"content_block_start","index":2,"content_block":{"type":"fallback","from_model":"claude-opus-5-5","to_model":"claude-opus-4-8"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":2}""") +
            event("content_block_start", """{"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":" carried on."}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":3}""") +
            event("content_block_start", """{"type":"content_block_start","index":4,"content_block":{"type":"tool_use","id":"toolu_new","name":"device_clock_now","input":{}}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":4,"delta":{"type":"input_json_delta","partial_json":"{}"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":4}""") +
            end("tool_use")
    }
}
