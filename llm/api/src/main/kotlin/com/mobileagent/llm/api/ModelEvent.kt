package com.mobileagent.llm.api

import com.mobileagent.core.common.HarnessError
import com.mobileagent.core.model.ContentBlock
import com.mobileagent.core.model.TokenUsage
import kotlinx.serialization.json.JsonElement

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

/** Provider-neutral streaming events emitted by every adapter. */
sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent

    data class ReasoningDelta(val text: String) : ModelEvent

    /** Opaque reasoning state the provider needs back on the next request (e.g. encrypted reasoning). */
    data class ReasoningState(val providerState: JsonElement) : ModelEvent

    data class ToolCallCompleted(val toolCall: ContentBlock.ToolCall) : ModelEvent

    data class Usage(val usage: TokenUsage) : ModelEvent

    data class Completed(val stopReason: StopReason) : ModelEvent

    data class Failed(val error: HarnessError) : ModelEvent
}
