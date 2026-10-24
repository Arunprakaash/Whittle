package app.whittle.llm.openai

import app.whittle.core.common.HarnessError
import app.whittle.core.common.IdGenerator
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.TokenUsage
import app.whittle.core.model.ToolCallId
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.StopReason
import app.whittle.llm.http.StreamEventParser
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Turns Chat Completions stream chunks into canonical [ModelEvent]s. Tool-call arguments arrive as
 * fragments keyed by index and are emitted once complete. Usage arrives after the finish reason,
 * so [Completed][ModelEvent.Completed] is only emitted from [finish].
 */
internal class OpenAiStreamParser(
    private val json: Json,
    private val idGenerator: IdGenerator,
) : StreamEventParser {
    private val pendingToolCalls = sortedMapOf<Int, PendingToolCall>()
    private var stopReason: StopReason? = null
    private var usage: TokenUsage? = null
    private var finished = false

    override fun onData(data: String): List<ModelEvent> {
        if (data == DONE_SENTINEL) return finish()
        val chunk = try {
            json.decodeFromString(ChatCompletionChunk.serializer(), data)
        } catch (exception: SerializationException) {
            return listOf(ModelEvent.Failed(HarnessError.Serialization("Unreadable stream chunk: ${exception.message}")))
        }
        chunk.error?.let { return listOf(ModelEvent.Failed(HarnessError.Provider(STREAM_ERROR_STATUS, it.message.orEmpty()))) }
        chunk.usage?.let { usage = it.toTokenUsage() }
        return chunk.choices.flatMap(::onChoice)
    }

    override fun finish(): List<ModelEvent> {
        if (finished) return emptyList()
        finished = true
        return buildList {
            addAll(drainToolCalls())
            usage?.let { add(ModelEvent.Usage(it)) }
            add(ModelEvent.Completed(stopReason ?: StopReason.OTHER))
        }
    }

    private fun onChoice(choice: ChunkChoice): List<ModelEvent> = buildList {
        choice.delta?.let { delta ->
            delta.reasoning?.takeIf { it.isNotEmpty() }?.let { add(ModelEvent.ReasoningDelta(it)) }
            delta.content?.takeIf { it.isNotEmpty() }?.let { add(ModelEvent.TextDelta(it)) }
            delta.toolCalls?.forEach(::accumulate)
        }
        choice.finishReason?.let {
            stopReason = mapStopReason(it)
            addAll(drainToolCalls())
        }
    }

    private fun accumulate(delta: ToolCallDelta) {
        val pending = pendingToolCalls.getOrPut(delta.index ?: (pendingToolCalls.keys.maxOrNull()?.plus(1) ?: 0)) { PendingToolCall() }
        delta.id?.let { pending.id = it }
        delta.function?.name?.let { pending.name.append(it) }
        delta.function?.arguments?.let { pending.arguments.append(it) }
    }

    private fun drainToolCalls(): List<ModelEvent> = pendingToolCalls.values.map {
        ModelEvent.ToolCallCompleted(
            ContentBlock.ToolCall(
                id = ToolCallId(it.id ?: idGenerator.newId()),
                toolName = it.name.toString(),
                argumentsJson = it.arguments.toString().ifBlank { EMPTY_ARGUMENTS },
            ),
        )
    }.also { pendingToolCalls.clear() }

    private fun mapStopReason(reason: String): StopReason = when (reason) {
        "stop" -> StopReason.END_TURN
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        "length" -> StopReason.MAX_TOKENS
        "content_filter" -> StopReason.REFUSAL
        else -> StopReason.OTHER
    }

    private fun WireUsage.toTokenUsage(): TokenUsage {
        val cached = promptTokensDetails?.cachedTokens ?: 0
        return TokenUsage(
            inputTokens = promptTokens - cached,
            outputTokens = completionTokens,
            cacheReadTokens = cached,
            cacheWriteTokens = 0,
        )
    }

    private class PendingToolCall {
        var id: String? = null
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private companion object {
        const val DONE_SENTINEL = "[DONE]"
        const val EMPTY_ARGUMENTS = "{}"

        /** Errors reported inside an HTTP 200 stream have no status code of their own. */
        const val STREAM_ERROR_STATUS = 200
    }
}
