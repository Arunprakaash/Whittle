package app.whittle.dev.cli

import app.whittle.agent.AskUserTool
import app.whittle.agent.DecisionAnswer
import app.whittle.agent.DecisionKind
import app.whittle.agent.RunEvent
import app.whittle.agent.RunInbox
import app.whittle.agent.RunInput
import app.whittle.core.model.RunId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Stands in for the app's inline decision and question cards during command-line testing. */
class ConsoleDecisions(private val mode: ApprovalMode, private val inbox: RunInbox) {
    suspend fun answer(runId: RunId, event: RunEvent.DecisionRequested) {
        if (event.decision.kind == DecisionKind.QUESTION) return answerQuestion(runId, event)
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

    /** Asks on the console in ASK mode; otherwise picks the first option so scripted runs keep going. */
    private suspend fun answerQuestion(runId: RunId, event: RunEvent.DecisionRequested) {
        val question = event.arguments[AskUserTool.QUESTION]?.jsonPrimitive?.contentOrNull.orEmpty()
        val options = event.arguments[AskUserTool.OPTIONS]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (options.isEmpty()) return
        println("\n[question] $question")
        options.forEachIndexed { index, option -> println("[question] ${index + 1}. $option") }
        val choice = when (mode) {
            ApprovalMode.ASK -> withContext(Dispatchers.IO) {
                print("[question] Pick 1-${options.size}: ")
                readlnOrNull()?.trim()?.toIntOrNull()?.let { options.getOrNull(it - 1) } ?: options.first()
            }
            ApprovalMode.ALWAYS, ApprovalMode.NEVER -> options.first().also { println("[question] auto-picked: $it") }
        }
        inbox.submit(runId, RunInput.Answer(event.decision.toolCall.id, choice))
    }
}
