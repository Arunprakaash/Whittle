package app.whittle.chat

import app.whittle.agent.PlanItem
import app.whittle.apps.PowerfulDeclaration
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.presentation.CapabilityPresentation
import app.whittle.tools.presentation.DecisionText
import app.whittle.tools.presentation.QuestionText
import app.whittle.attachments.Attachment
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
    data class User(
        override val key: String,
        val text: String,
        val steer: Boolean,
        /** Photos and files sent with it, saved in the workspace. */
        val attachments: List<Attachment> = emptyList(),
    ) : ThreadItem

    /** The agent's words: narration between steps while streaming; the last one in a run is its answer. */
    @Serializable
    @SerialName("narration")
    data class Narration(
        override val key: String,
        val text: String,
        val streaming: Boolean,
        /** The run's final words, which get a copy action (and the sources, when it cites any). */
        val answer: Boolean = false,
    ) : ThreadItem

    @Serializable
    @SerialName("steps")
    data class Steps(override val key: String, val steps: List<StepModel>) : ThreadItem

    /** An open card. Belongs to [runId], which may already have finished. */
    @Serializable
    @SerialName("decision")
    data class Decision(override val key: String, val runId: RunId, val toolCallId: ToolCallId, val text: DecisionText) : ThreadItem

    /** A question card; the picked option goes back to [runId] and stays shown as [answer]. */
    @Serializable
    @SerialName("question")
    data class Question(
        override val key: String,
        val runId: RunId,
        val toolCallId: ToolCallId,
        val text: QuestionText,
        val answer: String? = null,
    ) : ThreadItem

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

    /** The agent's plan for this task, kept up to date in place as steps start and finish. */
    @Serializable
    @SerialName("plan")
    data class Plan(override val key: String, val steps: List<PlanItem>) : ThreadItem

    /**
     * A server or watcher the agent started in Linux: a live card while it runs, with Preview once it serves. What
     * started it is kept, so Restart works after the app restarts, when the process itself is gone.
     */
    @Serializable
    @SerialName("process")
    data class Process(
        override val key: String,
        val processId: String,
        val name: String,
        val command: String,
        val port: Int?,
        /** The chat's project when it started, so Restart runs it from the same folder. */
        val project: String? = null,
    ) : ThreadItem

    /**
     * An app Whittle built, with what it needs to install it. [apkPath] is relative to the workspace and [versionCode]
     * is the build's own, so the card only ever installs what it was made for.
     */
    @Serializable
    @SerialName("app")
    data class App(
        override val key: String,
        val name: String,
        val packageName: String,
        val versionCode: Int,
        val apkPath: String,
        val permissions: List<String>,
        val powerful: List<PowerfulDeclaration>,
        /** The run read content from the web before this was built. */
        val fromTaintedRun: Boolean,
        val lastCrash: String? = null,
    ) : ThreadItem

    /** The pages the answer above cites, shown as a pill that opens into a list. */
    @Serializable
    @SerialName("sources")
    data class Sources(override val key: String, val sources: List<SourceModel>, val expanded: Boolean = false) : ThreadItem

    @Serializable
    @SerialName("notice")
    data class Notice(
        override val key: String,
        val kind: NoticeKind,
        val statusCode: Int? = null,
        /** What the model's own tool said, for notices that show it, such as when a Claude plan resets. */
        val detail: String? = null,
        /** For a Claude plan limit: which one, as the notice says it ("5-hour"), and when it reopens. */
        val limit: String? = null,
        val resetsAtMillis: Long? = null,
    ) : ThreadItem

    /** A run that was still working when the app closed. Continuing it is the user's call. */
    @Serializable
    @SerialName("interrupted")
    data class Interrupted(override val key: String, val runId: RunId) : ThreadItem
}

@Serializable
data class StepModel(
    val id: String,
    val icon: String,
    val label: String,
    val status: StepState,
    /** What ran and what came back, for the expandable step detail. */
    val inspection: String? = null,
    /** Host of the web page the step reads; its site icon replaces [icon]. */
    val site: String? = null,
    /** The file the step wrote, relative to the workspace; the step opens it in the file browser. */
    val file: String? = null,
)

@Serializable
enum class StepState { Running, Done, Failed, Blocked, NeedsAccess, Stopped }

@Serializable
enum class NoticeKind {
    Stopped, PausedBudget, PausedSteps, FailedKey, FailedNetwork, FailedProvider, FailedGeneric,
    ClaudeCodeSignedOut, ClaudeCodeLimit, ClaudeCodeFailed,
}

/** The task stopped because something went wrong, not because the user or a limit stopped it. */
val NoticeKind.isFailure: Boolean
    get() = this != NoticeKind.Stopped && this != NoticeKind.PausedBudget && this != NoticeKind.PausedSteps
