package app.whittle.agent

import app.whittle.core.common.HarnessError
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.TokenUsage
import app.whittle.policy.PolicyDecision

/** What the UI observes while a run progresses. */
sealed interface RunEvent {
    data class StepStarted(val step: Int) : RunEvent

    data class AssistantTextDelta(val text: String) : RunEvent

    data class ToolCallDecided(val toolCall: ContentBlock.ToolCall, val decision: PolicyDecision) : RunEvent

    data class ToolCallFinished(val result: ContentBlock.ToolResult) : RunEvent

    data class Completed(val finalMessage: Message, val usage: TokenUsage) : RunEvent

    data class Paused(val reason: PauseReason, val usage: TokenUsage) : RunEvent

    data class Failed(val error: HarnessError) : RunEvent
}
