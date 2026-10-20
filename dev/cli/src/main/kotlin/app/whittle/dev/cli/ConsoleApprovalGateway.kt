package app.whittle.dev.cli

import app.whittle.agent.ApprovalAnswer
import app.whittle.agent.ApprovalGateway
import app.whittle.agent.ApprovalRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stands in for the app's approval sheet during command-line testing. */
class ConsoleApprovalGateway(private val mode: ApprovalMode) : ApprovalGateway {
    override suspend fun request(request: ApprovalRequest): ApprovalAnswer {
        val tool = request.tool
        println("\n[approval] ${tool.id.value} (${tool.riskTier}) wants to run with ${request.arguments}")
        println("[approval] reason: ${request.reason::class.simpleName}, scope: ${request.scope}")
        return when (mode) {
            ApprovalMode.ALWAYS -> ApprovalAnswer.APPROVED.also { println("[approval] auto-approved") }
            ApprovalMode.NEVER -> ApprovalAnswer.DECLINED.also { println("[approval] auto-declined") }
            ApprovalMode.ASK -> withContext(Dispatchers.IO) {
                print("[approval] Allow? [y/N] ")
                if (readlnOrNull()?.trim()?.lowercase() == "y") ApprovalAnswer.APPROVED else ApprovalAnswer.DECLINED
            }
        }
    }
}
