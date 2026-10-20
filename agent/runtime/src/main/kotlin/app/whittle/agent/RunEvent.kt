package app.whittle.agent

import app.whittle.core.common.HarnessError
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.TokenUsage
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

    /** A call was parked for the user's choice. The run keeps going; show it as an inline card. */
    data class DecisionRequested(
        val decision: PendingDecision,
        val tool: ToolDefinition,
        val arguments: JsonObject,
    ) : RunEvent

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
}
