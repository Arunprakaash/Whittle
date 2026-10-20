package app.whittle.dev.cli

import app.whittle.agent.DecisionAnswer
import app.whittle.agent.RunEvent
import app.whittle.agent.RunInbox
import app.whittle.agent.RunInput
import app.whittle.core.model.RunId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stands in for the app's inline decision cards during command-line testing. */
class ConsoleDecisions(private val mode: ApprovalMode, private val inbox: RunInbox) {
    suspend fun answer(runId: RunId, event: RunEvent.DecisionRequested) {
        val tool = event.tool
        println("\n[decision] ${tool.id.value} (${tool.riskTier}) wants to run with ${event.arguments}")
        val answer = when (mode) {
            ApprovalMode.ALWAYS -> DecisionAnswer.APPROVED.also { println("[decision] auto-approved") }
            ApprovalMode.NEVER -> DecisionAnswer.DECLINED.also { println("[decision] auto-declined") }
            ApprovalMode.ASK -> withContext(Dispatchers.IO) {
                print("[decision] Allow? [y/N] ")
                if (readlnOrNull()?.trim()?.lowercase() == "y") DecisionAnswer.APPROVED else DecisionAnswer.DECLINED
            }
        }
        inbox.submit(runId, RunInput.Decision(event.decision.toolCall.id, answer))
    }
}
