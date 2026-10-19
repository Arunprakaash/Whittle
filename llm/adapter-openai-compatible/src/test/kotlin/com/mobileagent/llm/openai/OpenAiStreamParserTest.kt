package com.mobileagent.llm.openai

import com.mobileagent.core.common.IdGenerator
import com.mobileagent.core.model.ContentBlock
import com.mobileagent.core.model.TokenUsage
import com.mobileagent.core.model.ToolCallId
import com.mobileagent.llm.api.ModelEvent
import com.mobileagent.llm.api.StopReason
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
    fun `error chunk becomes a failure`() {
        assertIs<ModelEvent.Failed>(parser.onData("""{"error":{"message":"rate limited"}}""").single())
    }

    @Test
    fun `finish is idempotent`() {
        parser.finish()
        assertEquals(emptyList(), parser.finish())
    }
}
