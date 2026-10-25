package app.whittle.llm.openai

import app.whittle.core.common.IdGenerator
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.TokenUsage
import app.whittle.core.model.ToolCallId
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAiStreamParserTest {
    private val parser = OpenAiStreamParser(WireJson, IdGenerator { "generated-id" })

    @Test
    fun `text deltas then usage then completion`() {
        val events = listOf(
            """{"choices":[{"delta":{"content":"Hel"}}]}""",
            """{"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":100}}}""",
            "[DONE]",
        ).flatMap(parser::onData)

        assertEquals(
            listOf(
                ModelEvent.TextDelta("Hel"),
                ModelEvent.TextDelta("lo"),
                ModelEvent.Usage(TokenUsage(inputTokens = 20, outputTokens = 5, cacheReadTokens = 100, cacheWriteTokens = 0)),
                ModelEvent.Completed(StopReason.END_TURN),
            ),
            events,
        )
    }

    @Test
    fun `whole tool calls without an index, as Gemini sends them, stay separate`() {
        val events = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"id":"a","type":"function","function":{"name":"one","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"id":"b","type":"function","function":{"name":"two","arguments":"{\"x\":1}"}}]},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        ).flatMap(parser::onData)
        assertEquals(
            listOf(ContentBlock.ToolCall(ToolCallId("a"), "one", "{}"), ContentBlock.ToolCall(ToolCallId("b"), "two", "{\"x\":1}")),
            events.filterIsInstance<ModelEvent.ToolCallCompleted>().map { it.toolCall },
        )
    }

    @Test
    fun `a call sent in pieces without an index is put back together`() {
        val events = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"id":"call_1","function":{"name":"time_now","arguments":"{\"zo"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"ne\":\"UTC\"}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"id":"call_1","function":{"arguments":""}}]},"finish_reason":"tool_calls"}]}""",
        ).flatMap(parser::onData)
        assertEquals(
            listOf(ContentBlock.ToolCall(ToolCallId("call_1"), "time_now", """{"zone":"UTC"}""")),
            events.filterIsInstance<ModelEvent.ToolCallCompleted>().map { it.toolCall },
        )
    }

    @Test
    fun `tool call fragments are assembled by index`() {
        val events = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"time_","arguments":"{\"zo"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"now","arguments":"ne\":\"UTC\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        ).flatMap(parser::onData) + parser.finish()

        assertEquals(
            ModelEvent.ToolCallCompleted(ContentBlock.ToolCall(ToolCallId("call_1"), "time_now", """{"zone":"UTC"}""")),
            events.first(),
        )
        assertEquals(ModelEvent.Completed(StopReason.TOOL_USE), events.last())
    }

    @Test
    fun `missing tool call id is generated`() {
        val events = parser.onData("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"x"}}]},"finish_reason":"tool_calls"}]}""")
        val call = assertIs<ModelEvent.ToolCallCompleted>(events.single()).toolCall
        assertEquals(ToolCallId("generated-id"), call.id)
        assertEquals("{}", call.argumentsJson)
    }

    @Test
    fun `an empty tool call id is generated too`() {
        val events = parser.onData("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"","function":{"name":"x"}}]},"finish_reason":"tool_calls"}]}""")
        assertEquals(ToolCallId("generated-id"), assertIs<ModelEvent.ToolCallCompleted>(events.single()).toolCall.id)
    }

    @Test
    fun `a later fragment with an empty id keeps the first fragment's id`() {
        val events = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"x","arguments":"{"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"","function":{"arguments":"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        ).flatMap(parser::onData)
        assertEquals(ToolCallId("call_1"), assertIs<ModelEvent.ToolCallCompleted>(events.first()).toolCall.id)
    }

    @Test
    fun `error chunk becomes a failure`() {
        assertIs<ModelEvent.Failed>(parser.onData("""{"error":{"message":"rate limited"}}""").single())
    }

    @Test
    fun `finish is idempotent`() {
        parser.finish()
        assertEquals(emptyList(), parser.finish())
    }
}
