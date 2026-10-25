package app.whittle.llm.anthropic

import app.whittle.core.common.HarnessError
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.TokenUsage
import app.whittle.core.model.ToolCallId
import app.whittle.llm.api.HostedTool
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.StopReason
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.ServerToolUseBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Turns one response's stream into canonical events. Text and thinking stream as they arrive; tool calls, sources, usage
 * and the turn to send back are read from the finished message, which the SDK's accumulator assembles. A new instance is
 * used per request.
 */
internal class AnthropicStreamParser {
    private val accumulator = MessageAccumulator.create()
    private var stopped = false

    fun onEvent(event: RawMessageStreamEvent): List<ModelEvent> {
        accumulator.accumulate(event)
        if (event.isMessageStop()) stopped = true
        val delta = event.contentBlockDelta().orElse(null)?.delta()
        if (delta != null) {
            delta.text().orElse(null)?.let { return listOf(ModelEvent.TextDelta(it.text())) }
            delta.thinking().orElse(null)?.let { return listOf(ModelEvent.ReasoningDelta(it.thinking())) }
            return emptyList()
        }
        val search = event.contentBlockStart().orElse(null)?.contentBlock()?.serverToolUse()?.orElse(null)?.takeIf { it.name() == ServerToolUseBlock.Name.WEB_SEARCH }
        return listOfNotNull(search?.let { ModelEvent.HostedToolStarted(it.id(), HostedTool.WEB_SEARCH) })
    }

    /** Called once the stream ends: the finished turn's tool calls, sources, usage and state, ending with Completed. */
    fun finish(): List<ModelEvent> {
        if (!stopped) return listOf(ModelEvent.Failed(HarnessError.Network("The response ended before Claude finished")))
        val message = accumulator.message()
        val raw = Json.parseToJsonElement(jsonMapper().writeValueAsString(message)).jsonObject[CONTENT] as? JsonArray ?: JsonArray(emptyList())
        // After a fallback mid-turn, only what follows the marker is this turn's to act on.
        val current = message.content().drop(lastFallback(raw) + 1)
        return buildList {
            current.forEach { block ->
                block.toolUse().orElse(null)?.let { add(ModelEvent.ToolCallCompleted(ContentBlock.ToolCall(ToolCallId(it.id()), it.name(), json(it._input())))) }
                block.serverToolUse().orElse(null)?.takeIf { it.name() == ServerToolUseBlock.Name.WEB_SEARCH }?.let { add(searched(it)) }
                block.text().orElse(null)?.citations()?.orElse(null).orEmpty().forEach { citation ->
                    citation.webSearchResultLocation().orElse(null)?.let { add(ModelEvent.SourceCited(it.url(), it.title().orElse(null))) }
                }
            }
            // An empty turn (an early refusal, an empty end_turn) has nothing to send back: the API rejects empty content.
            AnthropicTurnCodec.echoable(raw).takeIf { it.isNotEmpty() }?.let { add(ModelEvent.ReasoningState(AnthropicTurnCodec.wrap(it))) }
            add(ModelEvent.Usage(usage(message)))
            add(ModelEvent.Completed(stopReason(message)))
        }
    }

    private fun lastFallback(raw: JsonArray): Int =
        raw.indexOfLast { ((it as? JsonObject)?.get(TYPE) as? JsonPrimitive)?.contentOrNull == AnthropicTurnCodec.FALLBACK }

    private fun searched(block: ServerToolUseBlock): ModelEvent.HostedToolFinished {
        val input = Json.parseToJsonElement(json(block._input())) as? JsonObject
        val query = (input?.get(QUERY) as? JsonPrimitive)?.contentOrNull
        return ModelEvent.HostedToolFinished(
            callId = block.id(),
            tool = HostedTool.WEB_SEARCH,
            action = SEARCH_ACTION,
            arguments = buildJsonObject { query?.let { put(QUERY, it) } },
        )
    }

    private fun usage(message: Message): TokenUsage {
        val usage = message.usage()
        return TokenUsage(
            inputTokens = usage.inputTokens(),
            outputTokens = usage.outputTokens(),
            cacheReadTokens = usage.cacheReadInputTokens().orElse(0L),
            cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L),
        )
    }

    private fun stopReason(message: Message): StopReason = when (message.stopReason().orElse(null)) {
        com.anthropic.models.messages.StopReason.END_TURN, com.anthropic.models.messages.StopReason.STOP_SEQUENCE -> StopReason.END_TURN
        com.anthropic.models.messages.StopReason.TOOL_USE -> StopReason.TOOL_USE
        com.anthropic.models.messages.StopReason.MAX_TOKENS, com.anthropic.models.messages.StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> StopReason.MAX_TOKENS
        com.anthropic.models.messages.StopReason.REFUSAL -> StopReason.REFUSAL
        else -> StopReason.OTHER
    }

    private fun json(value: JsonValue): String = jsonMapper().writeValueAsString(value)

    private companion object {
        const val CONTENT = "content"
        const val TYPE = "type"
        const val QUERY = "query"
        const val SEARCH_ACTION = "search"
    }
}
