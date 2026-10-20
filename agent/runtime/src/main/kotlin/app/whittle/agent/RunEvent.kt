package app.whittle.agent

import app.whittle.core.common.HarnessError
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.TokenUsage
import app.whittle.llm.api.HostedTool
import app.whittle.policy.PolicyDecision
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.JsonObject

/** What the UI observes while a run progresses. */
sealed interface RunEvent {
    data class StepStarted(val step: Int) : RunEvent

    data class AssistantTextDelta(val text: String) : RunEvent

    data class ToolCallDecided(val toolCall: ContentBlock.ToolCall, val decision: PolicyDecision) : RunEvent

    data class ToolCallFinished(val result: ContentBlock.ToolResult) : RunEvent

    /** The provider started one of its own tools, such as a web search. Policy already allowed it. */
    data class HostedToolStarted(val callId: String, val tool: HostedTool) : RunEvent

    /** The provider finished a hosted tool call; [action] and [arguments] say what it did. */
    data class HostedToolFinished(
        val callId: String,
        val tool: HostedTool,
        val action: String,
        val arguments: JsonObject,
    ) : RunEvent

    /** A call was parked for the user's choice. The run keeps going; show it as an inline card. */
    data class DecisionRequested(
        val decision: PendingDecision,
        val tool: ToolDefinition,
        val arguments: JsonObject,
    ) : RunEvent

    /** The answer cites [url] (from the provider's citations); [title] is the page title when known. */
    data class SourceCited(val url: String, val title: String?) : RunEvent

    /** The user picked [choice] on a question card. */
    data class QuestionAnswered(val decision: PendingDecision, val choice: String) : RunEvent

    /** A tool needs a capability the user hasn't granted; show a card asking for it. The run keeps going. */
    data class CapabilityRequested(val capability: CapabilityId, val toolCallId: ToolCallId) : RunEvent

    /** The user answered a card. [output] is set when an approved call ran. */
    data class DecisionResolved(
        val decision: PendingDecision,
        val answer: DecisionAnswer,
        val output: ToolOutput?,
    ) : RunEvent

    /** The model finished. [openDecisions] are cards still in the thread; answering one continues the run. */
    data class Completed(val finalMessage: Message, val usage: TokenUsage, val openDecisions: List<PendingDecision>) : RunEvent

    data class Paused(val reason: PauseReason, val usage: TokenUsage) : RunEvent

    data class Failed(val error: HarnessError) : RunEvent

    /** The provider failed for a passing reason; the same call is tried again after [delayMillis]. */
    data class ModelRetrying(val attempt: Int, val delayMillis: Long) : RunEvent
}
