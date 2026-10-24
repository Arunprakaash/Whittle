package app.whittle.dev.cli.eval

import app.whittle.dev.cli.CliSession
import app.whittle.dev.cli.CliSetup
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ReasoningEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

/** How one case went. */
@Serializable
data class CaseResult(
    val id: String,
    val passed: Boolean,
    val problems: List<String>,
    val steps: Int,
    val seconds: Double,
    val inputTokens: Long,
    val outputTokens: Long,
    val answer: String,
)

/** A suite's run against one model, as written to the report file. */
@Serializable
data class EvalReport(val suite: String, val provider: String, val model: String, val reasoning: String, val cases: List<CaseResult>) {
    val passed: Int get() = cases.count { it.passed }
}

/**
 * Runs every case of a suite in its own session: a fresh workspace with the case's files, an empty memory and its own
 * approval answer, so cases can't affect each other. Every run calls the model, so a suite costs what its runs cost.
 */
class EvalRunner(private val setup: CliSetup, private val scope: CoroutineScope) {
    suspend fun run(suite: EvalSuite, model: ModelDescriptor, effort: ReasoningEffort, onCase: (CaseResult) -> Unit): EvalReport {
        val results = suite.cases.map { case -> runCase(case, model, effort).also(onCase) }
        return EvalReport(suite.name, model.providerId.value, model.id.value, effort.name.lowercase(), results)
    }

    private suspend fun runCase(case: EvalCase, model: ModelDescriptor, effort: ReasoningEffort): CaseResult {
        val memory = Files.createTempFile("whittle-eval-memory", ".json").also { Files.delete(it) }
        val session = CliSession(setup, case.approve.mode, memory, scope)
        case.files.forEach { (path, text) -> File(session.workspace, path).apply { parentFile?.mkdirs() }.writeText(text) }
        val transcript = EvalTranscript()
        val started = System.nanoTime()
        session.run(model, effort, case.prompt, transcript::accept)
        val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
        val problems = problems(case.expect, transcript, session.workspace)
        return CaseResult(
            id = case.id,
            passed = problems.isEmpty(),
            problems = problems,
            steps = transcript.steps,
            seconds = seconds,
            inputTokens = transcript.usage.inputTokens + transcript.usage.cacheReadTokens + transcript.usage.cacheWriteTokens,
            outputTokens = transcript.usage.outputTokens,
            answer = transcript.answer.toString(),
        )
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private val json = Json { prettyPrint = true }

        fun write(report: EvalReport, file: File) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(EvalReport.serializer(), report))
        }
    }
}
