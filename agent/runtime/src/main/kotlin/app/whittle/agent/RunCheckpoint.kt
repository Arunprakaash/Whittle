package app.whittle.agent

import app.whittle.core.common.Outcome
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Conversation
import app.whittle.core.model.RunId
import app.whittle.core.model.TokenUsage
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ReasoningEffort
import app.whittle.policy.RunContext
import app.whittle.policy.RunMode
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.api.ToolId
import kotlinx.serialization.Serializable

@Serializable
enum class RunStatus { RUNNING, COMPLETED, FAILED, PAUSED }

@Serializable
enum class PauseReason {
    /** Only in runs saved by versions that capped tokens per task; resuming them simply continues. */
    TOKEN_BUDGET,
    STEP_LIMIT,

    /**
     * The user's approval or access to something on the phone is needed; the model isn't called again until
     * every such card is answered. Questions (ask_user) never pause a run.
     */
    AWAITING_USER,
}

/**
 * Everything needed to continue a run after process death. Saved after every step and before every
 * tool call, so a resumed run never repeats a side effect.
 */
@Serializable
data class RunCheckpoint(
    val runId: RunId,
    val model: ModelDescriptor,
    val mode: RunMode,
    val reasoningEffort: ReasoningEffort,
    val step: Int,
    val conversation: Conversation,
    val tainted: Boolean,
    val preApprovedTools: Set<ToolId>,
    val approvedOnceTools: Set<ToolId>,
    val usage: TokenUsage,
    val status: RunStatus,
    val pauseReason: PauseReason? = null,
    /** A tool call that had started when the checkpoint was written. Its outcome is unknown on resume. */
    val inFlightToolCall: ContentBlock.ToolCall? = null,
    /** Results gathered so far for the current step's tool calls. */
    val stepResults: List<ContentBlock.ToolResult> = emptyList(),
    /**
     * Tool calls parked for the user's choice. They never hold the run up: a run can complete with
     * cards still open, and answering one later continues the run from its checkpoint.
     */
    val pendingDecisions: List<PendingDecision> = emptyList(),
    /** An approved decision whose tool had started when the checkpoint was written. */
    val inFlightDecision: ContentBlock.ToolCall? = null,
    /** Capabilities a tool needed that the user hasn't granted yet; each shows a card in the thread. */
    val requestedCapabilities: Set<CapabilityId> = emptySet(),
    val workspace: WorkspaceId? = null,
    /** The agent's latest plan (update_plan), so the harness can tell when it stops with steps open. */
    val plan: List<PlanItem>? = null,
    /** The model was already sent back once to finish its plan in this run. */
    val planReminded: Boolean = false,
    /** True once a tool returned the user's personal data; with [tainted] it gates web reads. */
    val personalDataRead: Boolean = false,
) {
    fun runContext(): RunContext = RunContext(
        mode = mode,
        tainted = tainted,
        modelTrustTier = model.trustTier,
        preApprovedTools = preApprovedTools,
        approvedOnceTools = approvedOnceTools,
        personalDataRead = personalDataRead,
    )
}

interface RunCheckpointStore {
    suspend fun save(checkpoint: RunCheckpoint): Outcome<Unit>

    /** Returns null when no checkpoint exists for [runId]. */
    suspend fun load(runId: RunId): Outcome<RunCheckpoint?>
}
