package app.whittle.agent

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.policy.ApprovalScope
import app.whittle.policy.DecisionReason
import app.whittle.policy.RunMode
import app.whittle.tools.api.ToolDefinition
import kotlinx.serialization.json.JsonObject

data class ApprovalRequest(
    val runId: RunId,
    val toolCallId: ToolCallId,
    val tool: ToolDefinition,
    val arguments: JsonObject,
    val reason: DecisionReason,
    val scope: ApprovalScope,
    val mode: RunMode,
)

enum class ApprovalAnswer { APPROVED, DECLINED }

/**
 * Asks the user. Live runs show an in-app sheet; unattended runs post a notification whose Approve
 * action requires an unlocked phone. Only native UI can answer; content and models never can.
 */
fun interface ApprovalGateway {
    suspend fun request(request: ApprovalRequest): ApprovalAnswer
}
