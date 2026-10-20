package app.whittle.chat

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.design.components.DecisionUi
import app.whittle.design.components.QuestionUi
import app.whittle.tools.api.CapabilityId

/** One entry in the conversation, in the order the user reads it. */
sealed interface ThreadItem {
    val key: String

    data class User(override val key: String, val text: String, val steer: Boolean) : ThreadItem

    /** The agent's words: narration between steps while streaming; the last one in a run is its answer. */
    data class Narration(override val key: String, val text: String, val streaming: Boolean) : ThreadItem

    data class Steps(override val key: String, val steps: List<StepModel>) : ThreadItem

    /** An open card. Belongs to [runId], which may already have finished. */
    data class Decision(
        override val key: String,
        val runId: RunId,
        val toolCallId: ToolCallId,
        val ui: DecisionUi,
        val approvedOutcome: String,
        val declinedOutcome: String,
    ) : ThreadItem

    /** An open question card; the picked option goes back to [runId]. */
    data class Question(
        override val key: String,
        val runId: RunId,
        val toolCallId: ToolCallId,
        val ui: QuestionUi,
    ) : ThreadItem

    /** A card asking for a capability a tool needs, such as reading contacts. */
    data class Capability(
        override val key: String,
        val runId: RunId,
        val capability: CapabilityId,
        val ui: DecisionUi,
        val grantedOutcome: String,
        val deniedOutcome: String,
    ) : ThreadItem

    data class Resolved(override val key: String, val outcome: String, val declined: Boolean) : ThreadItem

    /** A finished run's steps, folded into one chip. */
    data class Summary(
        override val key: String,
        val stepCount: Int,
        val seconds: Long,
        val expanded: Boolean,
        val folded: List<ThreadItem>,
    ) : ThreadItem

    data class Notice(override val key: String, val kind: NoticeKind, val statusCode: Int? = null) : ThreadItem
}

data class StepModel(val id: String, val icon: String, val label: String, val status: StepState)

enum class StepState { Running, Done, Failed, Blocked, NeedsAccess, Stopped }

enum class NoticeKind { Stopped, PausedBudget, PausedSteps, FailedKey, FailedNetwork, FailedProvider, FailedGeneric }
