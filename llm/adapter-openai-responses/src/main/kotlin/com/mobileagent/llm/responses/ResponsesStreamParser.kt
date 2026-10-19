package com.mobileagent.llm.responses

import com.mobileagent.core.common.HarnessError
import com.mobileagent.core.model.ContentBlock
import com.mobileagent.core.model.TokenUsage
import com.mobileagent.core.model.ToolCallId
import com.mobileagent.llm.api.ModelEvent
import com.mobileagent.llm.api.StopReason
import com.mobileagent.llm.http.StreamEventParser
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Turns Responses API stream events into canonical [ModelEvent]s. */
internal class ResponsesStreamParser : StreamEventParser {
    private var sawToolCall = false
    private var stopReason: StopReason? = null
    private var usage: TokenUsage? = null
    private var finished = false

    override fun onData(data: String): List<ModelEvent> {
        val event = try {
            WireJson.parseToJsonElement(data).jsonObject
        } catch (exception: SerializationException) {
            return listOf(ModelEvent.Failed(HarnessError.Serialization("Unreadable stream event: ${exception.message}")))
        } catch (exception: IllegalArgumentException) {
            return listOf(ModelEvent.Failed(HarnessError.Serialization("Stream event is not an object: ${exception.message}")))
        }
        return when (event.string(Field.TYPE)) {
            EventType.OUTPUT_TEXT_DELTA -> listOfNotNull(event.string(Field.DELTA)?.let(ModelEvent::TextDelta))
            EventType.REASONING_SUMMARY_DELTA -> listOfNotNull(event.string(Field.DELTA)?.let(ModelEvent::ReasoningDelta))
            EventType.OUTPUT_ITEM_DONE -> onItemDone(event.obj(Field.ITEM))
            EventType.COMPLETED -> onFinished(event.obj(Field.RESPONSE), incomplete = false)
            EventType.INCOMPLETE -> onFinished(event.obj(Field.RESPONSE), incomplete = true)
            EventType.FAILED -> listOf(failure(event.obj(Field.RESPONSE)?.obj(Field.ERROR)))
            EventType.ERROR -> listOf(failure(event))
            else -> emptyList()
        }
    }

    override fun finish(): List<ModelEvent> {
        if (finished) return emptyList()
        finished = true
        return listOfNotNull(usage?.let(ModelEvent::Usage)) +
            ModelEvent.Completed(stopReason ?: if (sawToolCall) StopReason.TOOL_USE else StopReason.OTHER)
    }

    private fun onItemDone(item: JsonObject?): List<ModelEvent> = when (item?.string(Field.TYPE)) {
        ItemType.FUNCTION_CALL -> {
            sawToolCall = true
            listOf(
                ModelEvent.ToolCallCompleted(
                    ContentBlock.ToolCall(
                        id = ToolCallId(item.string(Field.CALL_ID).orEmpty()),
                        toolName = item.string(Field.NAME).orEmpty(),
                        argumentsJson = item.string(Field.ARGUMENTS)?.ifBlank { null } ?: EMPTY_ARGUMENTS,
                    ),
                ),
            )
        }
        ItemType.REASONING -> listOf(ModelEvent.ReasoningState(ReasoningStateCodec.wrap(item)))
        else -> emptyList()
    }

    private fun onFinished(response: JsonObject?, incomplete: Boolean): List<ModelEvent> {
        usage = response?.obj(Field.USAGE)?.let(::toTokenUsage)
        stopReason = when {
            !incomplete && sawToolCall -> StopReason.TOOL_USE
            !incomplete -> StopReason.END_TURN
            else -> when (response?.obj(Field.INCOMPLETE_DETAILS)?.string(Field.REASON)) {
                IncompleteReason.MAX_OUTPUT_TOKENS -> StopReason.MAX_TOKENS
                IncompleteReason.CONTENT_FILTER -> StopReason.REFUSAL
                else -> StopReason.OTHER
            }
        }
        return finish()
    }

    private fun toTokenUsage(usage: JsonObject): TokenUsage {
        val input = usage.long(Field.INPUT_TOKENS)
        val details = usage.obj(Field.INPUT_TOKENS_DETAILS)
        val cached = details?.long(Field.CACHED_TOKENS) ?: 0
        return TokenUsage(
            inputTokens = input - cached,
            outputTokens = usage.long(Field.OUTPUT_TOKENS),
            cacheReadTokens = cached,
            cacheWriteTokens = details?.long(Field.CACHE_WRITE_TOKENS) ?: 0,
        )
    }

    private fun failure(error: JsonObject?): ModelEvent.Failed =
        ModelEvent.Failed(HarnessError.Provider(STREAM_ERROR_STATUS, error?.string(Field.MESSAGE) ?: UNKNOWN_ERROR))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0

    private object IncompleteReason {
        const val MAX_OUTPUT_TOKENS = "max_output_tokens"
        const val CONTENT_FILTER = "content_filter"
    }

    private companion object {
        const val EMPTY_ARGUMENTS = "{}"
        const val UNKNOWN_ERROR = "Unknown provider error"

        /** Errors reported inside an HTTP 200 stream have no status code of their own. */
        const val STREAM_ERROR_STATUS = 200
    }
}
