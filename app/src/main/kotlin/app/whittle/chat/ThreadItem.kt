package app.whittle.chat

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.presentation.CapabilityPresentation
import app.whittle.tools.presentation.DecisionText
import app.whittle.tools.presentation.QuestionText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry in the conversation, in the order the user reads it. Saved with the chat, so it holds
 * the words the presenter produced, not UI objects; the screen maps these to components.
 */
@Serializable
sealed interface ThreadItem {
    val key: String

    @Serializable
    @SerialName("user")
    data class User(override val key: String, val text: String, val steer: Boolean) : ThreadItem

    /** The agent's words: narration between steps while streaming; the last one in a run is its answer. */
    @Serializable
    @SerialName("narration")
    data class Narration(override val key: String, val text: String, val streaming: Boolean) : ThreadItem

    @Serializable
    @SerialName("steps")
    data class Steps(override val key: String, val steps: List<StepModel>) : ThreadItem

    /** An open card. Belongs to [runId], which may already have finished. */
    @Serializable
    @SerialName("decision")
    data class Decision(override val key: String, val runId: RunId, val toolCallId: ToolCallId, val text: DecisionText) : ThreadItem

    /** An open question card; the picked option goes back to [runId]. */
    @Serializable
    @SerialName("question")
    data class Question(override val key: String, val runId: RunId, val toolCallId: ToolCallId, val text: QuestionText) : ThreadItem

    /** A card asking for a capability a tool needs, such as reading contacts. */
    @Serializable
    @SerialName("capability")
    data class Capability(
        override val key: String,
        val runId: RunId,
        val capability: CapabilityId,
        val presentation: CapabilityPresentation,
    ) : ThreadItem

    @Serializable
    @SerialName("resolved")
    data class Resolved(override val key: String, val outcome: String, val declined: Boolean) : ThreadItem

    /** A finished run's steps, folded into one chip. */
    @Serializable
    @SerialName("summary")
    data class Summary(
        override val key: String,
        val stepCount: Int,
        val seconds: Long,
        val expanded: Boolean,
        val folded: List<ThreadItem>,
    ) : ThreadItem

    @Serializable
    @SerialName("notice")
    data class Notice(override val key: String, val kind: NoticeKind, val statusCode: Int? = null) : ThreadItem

    /** A run that was still working when the app closed. Continuing it is the user's call. */
    @Serializable
    @SerialName("interrupted")
    data class Interrupted(override val key: String, val runId: RunId) : ThreadItem
}

@Serializable
data class StepModel(val id: String, val icon: String, val label: String, val status: StepState)

@Serializable
enum class StepState { Running, Done, Failed, Blocked, NeedsAccess, Stopped }

@Serializable
enum class NoticeKind { Stopped, PausedBudget, PausedSteps, FailedKey, FailedNetwork, FailedProvider, FailedGeneric }
