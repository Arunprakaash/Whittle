package app.whittle.llm.responses

import app.whittle.core.model.ContentBlock
import app.whittle.core.model.TokenUsage
import app.whittle.core.model.ToolCallId
import app.whittle.llm.api.HostedTool
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.StopReason
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ResponsesStreamParserTest {
    private val parser = ResponsesStreamParser()

    @Test
    fun `reasoning item, function calls and usage from a real stream shape`() {
        val events = listOf(
            """{"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}""",
            """{"type":"response.output_item.done","item":{"id":"rs_1","type":"reasoning","content":[],"encrypted_content":"ENC","summary":[],"status":"completed"}}""",
            """{"type":"response.function_call_arguments.delta","delta":"{\"timeZone\"","item_id":"fc_1"}""",
            """{"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","status":"completed","arguments":"{\"timeZone\":\"Asia/Tokyo\"}","call_id":"call_1","name":"device_clock_now"}}""",
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":120,"input_tokens_details":{"cached_tokens":100,"cache_write_tokens":0},"output_tokens":30}}}""",
        ).flatMap(parser::onData)

        val state = assertIs<ModelEvent.ReasoningState>(events[0])
        val item = ReasoningStateCodec.unwrap(state.providerState)!!
        assertEquals("ENC", item.getValue("encrypted_content").jsonPrimitive.content)
        assertNull(item["status"])
        assertEquals(
            ModelEvent.ToolCallCompleted(ContentBlock.ToolCall(ToolCallId("call_1"), "device_clock_now", """{"timeZone":"Asia/Tokyo"}""")),
            events[1],
        )
        assertEquals(ModelEvent.Usage(TokenUsage(inputTokens = 20, outputTokens = 30, cacheReadTokens = 100, cacheWriteTokens = 0)), events[2])
        assertEquals(ModelEvent.Completed(StopReason.TOOL_USE), events[3])
        assertEquals(emptyList(), parser.finish())
    }

    @Test
    fun `text deltas end the turn`() {
        val events = listOf(
            """{"type":"response.output_text.delta","delta":"Hi "}""",
            """{"type":"response.output_text.delta","delta":"there"}""",
            """{"type":"response.completed","response":{"usage":{"input_tokens":5,"output_tokens":2}}}""",
        ).flatMap(parser::onData)

        assertEquals(listOf(ModelEvent.TextDelta("Hi "), ModelEvent.TextDelta("there")), events.take(2))
        assertEquals(ModelEvent.Completed(StopReason.END_TURN), events.last())
    }

    @Test
    fun `incomplete response reports the reason`() {
        val events = parser.onData(
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":5,"output_tokens":800}}}""",
        )
        assertEquals(ModelEvent.Completed(StopReason.MAX_TOKENS), events.last())
    }

    @Test
    fun `failed response and error events become failures`() {
        assertIs<ModelEvent.Failed>(parser.onData("""{"type":"response.failed","response":{"error":{"message":"overloaded"}}}""").single())
        assertIs<ModelEvent.Failed>(parser.onData("""{"type":"error","message":"bad request"}""").single())
    }

    @Test
    fun `hosted web search is reported as it starts and with what it did`() {
        val events = listOf(
            """{"type":"response.output_item.added","item":{"id":"ws_1","type":"web_search_call","status":"in_progress"}}""",
            """{"type":"response.output_item.done","item":{"id":"ws_1","type":"web_search_call","status":"completed","action":{"type":"search","queries":["Chennai weather today"],"query":"Chennai weather today"}}}""",
            """{"type":"response.output_item.done","item":{"id":"ws_2","type":"web_search_call","status":"completed","action":{"type":"open_page","url":"https://example.com/forecast"}}}""",
            """{"type":"response.output_item.done","item":{"id":"ws_3","type":"web_search_call","status":"completed","action":{"type":"search","queries":["only in queries"]}}}""",
        ).flatMap(parser::onData)

        assertEquals(ModelEvent.HostedToolStarted("ws_1", HostedTool.WEB_SEARCH), events[0])
        val search = assertIs<ModelEvent.HostedToolFinished>(events[1])
        assertEquals("search", search.action)
        assertEquals("Chennai weather today", search.arguments.getValue("query").jsonPrimitive.content)
        val open = assertIs<ModelEvent.HostedToolFinished>(events[2])
        assertEquals("open_page", open.action)
        assertEquals("https://example.com/forecast", open.arguments.getValue("url").jsonPrimitive.content)
        assertEquals("only in queries", assertIs<ModelEvent.HostedToolFinished>(events[3]).arguments.getValue("query").jsonPrimitive.content)
    }

    @Test
    fun `url citations on the answer become cited sources`() {
        val events = parser.onData(
            """{"type":"response.output_item.done","item":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Sunny.","annotations":[{"type":"url_citation","start_index":0,"end_index":5,"title":"Chennai forecast","url":"https://example.com/chennai?utm_source=openai"},{"type":"file_citation","file_id":"f"}]}]}}""",
        )
        assertEquals(listOf(ModelEvent.SourceCited("https://example.com/chennai?utm_source=openai", "Chennai forecast")), events)
    }

    @Test
    fun `unknown events are ignored`() {
        assertEquals(emptyList(), parser.onData("""{"type":"response.content_part.added","part":{}}"""))
    }
}
