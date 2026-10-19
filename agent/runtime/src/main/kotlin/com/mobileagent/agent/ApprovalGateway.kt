package com.mobileagent.agent

import com.mobileagent.core.model.RunId
import com.mobileagent.core.model.ToolCallId
import com.mobileagent.policy.ApprovalScope
import com.mobileagent.policy.DecisionReason
import com.mobileagent.policy.RunMode
import com.mobileagent.tools.api.ToolDefinition
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
