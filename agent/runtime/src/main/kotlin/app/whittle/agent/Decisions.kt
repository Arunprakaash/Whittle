package app.whittle.agent

import app.whittle.core.model.ContentBlock
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.policy.ApprovalScope
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.api.ToolId
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A tool call the policy engine wants the user to decide on. The run never waits for it: the call is
 * parked here, the model is told it is waiting, and the run carries on with independent work. Only the
 * exact parked call can run when the user approves it.
 */
@Serializable
data class PendingDecision(
    val toolCall: ContentBlock.ToolCall,
    val toolId: ToolId,
    val scope: ApprovalScope,
    /** The step that proposed the call; lets the UI place the card in the thread. */
    val step: Int,
    val kind: DecisionKind = DecisionKind.APPROVAL,
)

@Serializable
enum class DecisionKind {
    /** May this call run? Answered with approve or decline. */
    APPROVAL,

    /** The model asked the user to choose; answered with one of its options. */
    QUESTION,
}

@Serializable
enum class DecisionAnswer { APPROVED, DECLINED }

/** What the user can send to a run while it works or while it waits. */
sealed interface RunInput {
    /**
     * Extra instructions typed into the composer mid-run, with the workspace paths of any photos attached to them.
     * Applied at the next step boundary.
     */
    data class Steer(val text: String, val images: List<String> = emptyList()) : RunInput

    /** The user's choice on a decision card. */
    data class Decision(val toolCallId: ToolCallId, val answer: DecisionAnswer) : RunInput

    /** The option the user picked on a question card. */
    data class Answer(val toolCallId: ToolCallId, val choice: String) : RunInput

    /**
     * The user granted or refused a capability a tool asked for, such as reading contacts. [setupFailure] is set, with
     * [granted] false, when the user allowed it but setting it up failed; it says why.
     */
    data class CapabilityChanged(val capability: CapabilityId, val granted: Boolean, val setupFailure: String? = null) : RunInput
}

/**
 * Per-run queue between the UI and the agent loop. Only native UI writes to it; model output and tool
 * results never can. The loop drains it at every step boundary, so input never interrupts a model call
 * or a running tool.
 */
class RunInbox {
    private val queues = ConcurrentHashMap<RunId, ConcurrentLinkedQueue<RunInput>>()

    fun submit(runId: RunId, input: RunInput) {
        queues.computeIfAbsent(runId) { ConcurrentLinkedQueue() }.add(input)
    }

    fun hasInput(runId: RunId): Boolean = queues[runId]?.isNotEmpty() == true

    fun drain(runId: RunId): List<RunInput> {
        val queue = queues[runId] ?: return emptyList()
        return generateSequence { queue.poll() }.toList()
    }
}
