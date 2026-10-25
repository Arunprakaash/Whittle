package app.whittle.dev.cli.eval

import app.whittle.dev.cli.ApprovalMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A fixed set of tasks to run against a model, so a change to the prompt, the policy or a tool shows its effect. */
@Serializable
data class EvalSuite(val name: String, val cases: List<EvalCase>) {
    init {
        require(cases.map { it.id }.distinct().size == cases.size) { "Case ids must be unique" }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        fun read(file: File): EvalSuite = json.decodeFromString(serializer(), file.readText())
    }
}

/**
 * One task. [files] are put in its fresh workspace first. Every approval is answered with [approve]: an eval never waits
 * for someone at the terminal, and questions get their first option.
 */
@Serializable
data class EvalCase(
    val id: String,
    val prompt: String,
    val files: Map<String, String> = emptyMap(),
    val approve: Approval = Approval.NEVER,
    val expect: Expectations,
) {
    @Serializable
    enum class Approval(val mode: ApprovalMode) { ALWAYS(ApprovalMode.ALWAYS), NEVER(ApprovalMode.NEVER) }
}

/**
 * What a passing run shows. [called]: tools the model asked for; [ran] and [notRan]: tools that did or didn't run (after
 * policy and approvals); [asked]: tools that needed the user's approval; [answerContains]: words in the final answer, in any
 * case; [files]: text each workspace file must contain afterwards; [finishes]: the run ended normally.
 */
@Serializable
data class Expectations(
    val called: List<String> = emptyList(),
    val ran: List<String> = emptyList(),
    val notRan: List<String> = emptyList(),
    val asked: List<String> = emptyList(),
    val answerContains: List<String> = emptyList(),
    val files: Map<String, List<String>> = emptyMap(),
    val finishes: Boolean = true,
)
