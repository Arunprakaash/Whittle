package com.mobileagent.agent

import com.mobileagent.core.common.Outcome
import com.mobileagent.core.model.ContentBlock
import com.mobileagent.core.model.Conversation
import com.mobileagent.core.model.RunId
import com.mobileagent.core.model.TokenUsage
import com.mobileagent.llm.api.ModelDescriptor
import com.mobileagent.llm.api.ReasoningEffort
import com.mobileagent.policy.RunContext
import com.mobileagent.policy.RunMode
import com.mobileagent.tools.api.ToolId
import kotlinx.serialization.Serializable

@Serializable
enum class RunStatus { RUNNING, COMPLETED, FAILED, PAUSED }

@Serializable
enum class PauseReason { TOKEN_BUDGET, STEP_LIMIT }

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
    /** Tokens this run may use before it pauses and asks the user to continue. */
    val tokenBudget: Long,
    val status: RunStatus,
    val pauseReason: PauseReason? = null,
    /** A tool call that had started when the checkpoint was written. Its outcome is unknown on resume. */
    val inFlightToolCall: ContentBlock.ToolCall? = null,
    /** Results gathered so far for the current step's tool calls. */
    val stepResults: List<ContentBlock.ToolResult> = emptyList(),
) {
    fun runContext(): RunContext = RunContext(
        mode = mode,
        tainted = tainted,
        modelTrustTier = model.trustTier,
        preApprovedTools = preApprovedTools,
        approvedOnceTools = approvedOnceTools,
    )
}

interface RunCheckpointStore {
    suspend fun save(checkpoint: RunCheckpoint): Outcome<Unit>

    /** Returns null when no checkpoint exists for [runId]. */
    suspend fun load(runId: RunId): Outcome<RunCheckpoint?>
}
