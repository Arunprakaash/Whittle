package app.whittle.dev.cli.eval

import app.whittle.agent.DecisionAnswer
import app.whittle.agent.DecisionKind
import app.whittle.agent.RunEvent
import app.whittle.core.model.TokenUsage
import java.io.File

/** What one run did, as an eval checks it: gathered from its events. */
class EvalTranscript {
    private val toolNames = mutableMapOf<String, String>()
    val called = mutableListOf<String>()
    val ran = mutableListOf<String>()
    val asked = mutableListOf<String>()
    val answer = StringBuilder()
    var finished = false
        private set
    var failure: String? = null
        private set
    var steps = 0
        private set
    var usage: TokenUsage = TokenUsage.Zero
        private set

    fun accept(event: RunEvent) {
        when (event) {
            is RunEvent.StepStarted -> steps = event.step
            is RunEvent.ToolCallDecided -> {
                toolNames[event.toolCall.id.value] = event.toolCall.toolName
                called += event.toolCall.toolName
            }
            is RunEvent.ToolCallFinished -> toolNames[event.result.toolCallId.value]?.let { if (!event.result.isError) ran += it }
            is RunEvent.DecisionRequested -> if (event.decision.kind == DecisionKind.APPROVAL) asked += event.decision.toolCall.toolName
            is RunEvent.DecisionResolved -> if (event.answer == DecisionAnswer.APPROVED && event.output?.isError != true) ran += event.decision.toolCall.toolName
            is RunEvent.Completed -> {
                finished = true
                usage = event.usage
                answer.clear().append(event.finalMessage.text)
            }
            is RunEvent.Paused -> {
                usage = event.usage
                failure = "paused: ${event.reason}"
            }
            is RunEvent.Failed -> failure = event.error.toString()
            else -> Unit
        }
    }
}

/** Why [transcript] doesn't meet [expect]; empty when it does. [workspace] is the run's folder, for [Expectations.files]. */
fun problems(expect: Expectations, transcript: EvalTranscript, workspace: File): List<String> = buildList {
    if (expect.finishes && !transcript.finished) add("didn't finish (${transcript.failure ?: "no answer"})")
    expect.called.filterNot { it in transcript.called }.forEach { add("never called $it") }
    expect.ran.filterNot { it in transcript.ran }.forEach { add("$it didn't run") }
    expect.notRan.filter { it in transcript.ran }.forEach { add("$it ran, and shouldn't have") }
    expect.asked.filterNot { it in transcript.asked }.forEach { add("$it ran without asking") }
    val answer = transcript.answer.toString()
    expect.answerContains.filterNot { answer.contains(it, ignoreCase = true) }.forEach { add("the answer doesn't mention \"$it\"") }
    expect.files.forEach { (path, texts) ->
        val file = File(workspace, path)
        if (!file.isFile) {
            add("$path wasn't written")
        } else {
            val content = file.readText()
            texts.filterNot { content.contains(it) }.forEach { add("$path doesn't contain \"$it\"") }
        }
    }
}
