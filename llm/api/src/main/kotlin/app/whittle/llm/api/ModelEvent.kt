package app.whittle.llm.api

import app.whittle.core.common.HarnessError
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.TokenUsage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

/** Provider-neutral streaming events emitted by every adapter. */
sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent

    data class ReasoningDelta(val text: String) : ModelEvent

    /** Opaque reasoning state the provider needs back on the next request (e.g. encrypted reasoning). */
    data class ReasoningState(val providerState: JsonElement) : ModelEvent

    data class ToolCallCompleted(val toolCall: ContentBlock.ToolCall) : ModelEvent

    /** The provider started running one of its own tools, such as a web search. */
    data class HostedToolStarted(val callId: String, val tool: HostedTool) : ModelEvent

    /**
     * The provider finished a hosted tool call. [action] is what it did (for web search: "search",
     * "open_page" or "find_in_page") and [arguments] what it did it with (a query, a url, ...).
     */
    data class HostedToolFinished(
        val callId: String,
        val tool: HostedTool,
        val action: String,
        val arguments: JsonObject,
    ) : ModelEvent

    /** The answer cites [url]; [title] is the page title when the provider reports one. */
    data class SourceCited(val url: String, val title: String?) : ModelEvent

    data class Usage(val usage: TokenUsage) : ModelEvent

    data class Completed(val stopReason: StopReason) : ModelEvent

    data class Failed(val error: HarnessError) : ModelEvent
}
