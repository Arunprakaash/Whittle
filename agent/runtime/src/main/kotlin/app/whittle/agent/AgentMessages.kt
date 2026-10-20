package app.whittle.agent

import app.whittle.core.common.getOrElse
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.PromptTemplate
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable

/**
 * Text the harness sends to the model as tool results. Kept as reviewed config, not code.
 * Each template may only use the placeholders listed for it; this is checked when the file loads.
 */
@Serializable
data class AgentMessages(
    override val schemaVersion: Int,
    val unknownTool: String,
    val invalidArguments: String,
    val policyDenied: String,
    /** Tool result for a call parked for the user's choice. */
    val awaitingUser: String,
    /** Harness note once the user approves a parked call and it runs. */
    val decisionApproved: String,
    /** Same, when the tool's output came from outside the phone and must stay information only. */
    val decisionApprovedUntrusted: String,
    val decisionDeclined: String,
    val decisionFailed: String,
    /** Tool result for a question shown to the user as a card. */
    val questionShown: String,
    val questionAnswered: String,
    /** Tool result when a tool needs a capability the user hasn't granted. */
    val capabilityMissing: String,
    val capabilityGranted: String,
    val capabilityDenied: String,
    val interruptedToolCall: String,
    val toolFailed: String,
    val outputTruncated: String,
    /** Sent once when the model tries to finish while its plan still has open steps. */
    val planUnfinished: String,
    /** A long tool output's start and end, with where the whole of it was saved in the workspace. */
    val outputSaved: String,
    /** One remembered fact in the system prompt, with the id forget takes. */
    val memoryLine: String,
    /** The system prompt's memory section when nothing is remembered yet. */
    val noMemories: String,
    /** remember succeeded; [ID] is what forget takes. */
    val memorySaved: String,
    val memoryEmpty: String,
    val memoryTooLong: String,
    val memoryFull: String,
    val memoryForgotten: String,
    val memoryNotFound: String,
) : VersionedConfig {
    init {
        requirePlaceholders(unknownTool, TOOL)
        requirePlaceholders(invalidArguments, TOOL, DETAIL)
        requirePlaceholders(policyDenied, TOOL)
        requirePlaceholders(awaitingUser, TOOL)
        requirePlaceholders(decisionApproved, TOOL, OUTPUT)
        requirePlaceholders(decisionApprovedUntrusted, TOOL, SOURCE, OUTPUT)
        requirePlaceholders(decisionDeclined, TOOL)
        requirePlaceholders(decisionFailed, TOOL, DETAIL)
        requirePlaceholders(questionShown, TOOL)
        requirePlaceholders(questionAnswered, QUESTION, ANSWER)
        requirePlaceholders(capabilityMissing, TOOL, CAPABILITY)
        requirePlaceholders(capabilityGranted, CAPABILITY)
        requirePlaceholders(capabilityDenied, CAPABILITY)
        requirePlaceholders(interruptedToolCall, TOOL)
        requirePlaceholders(toolFailed, TOOL, DETAIL)
        requirePlaceholders(outputTruncated, LIMIT)
        requirePlaceholders(planUnfinished, STEPS)
        requirePlaceholders(outputSaved, HEAD, TAIL, CHARS, PATH)
        requirePlaceholders(memoryLine, ID, TEXT)
        requirePlaceholders(noMemories)
        requirePlaceholders(memorySaved, ID)
        requirePlaceholders(memoryEmpty)
        requirePlaceholders(memoryTooLong, LIMIT)
        requirePlaceholders(memoryFull, LIMIT)
        requirePlaceholders(memoryForgotten)
        requirePlaceholders(memoryNotFound, ID)
    }

    fun unknownTool(tool: String): String = render(unknownTool, TOOL to tool)

    fun invalidArguments(tool: String, detail: String): String = render(invalidArguments, TOOL to tool, DETAIL to detail)

    fun policyDenied(tool: String): String = render(policyDenied, TOOL to tool)

    fun awaitingUser(tool: String): String = render(awaitingUser, TOOL to tool)

    fun decisionApproved(tool: String, output: String): String = render(decisionApproved, TOOL to tool, OUTPUT to output)

    fun decisionApprovedUntrusted(tool: String, source: String, output: String): String =
        render(decisionApprovedUntrusted, TOOL to tool, SOURCE to source, OUTPUT to output)

    fun decisionDeclined(tool: String): String = render(decisionDeclined, TOOL to tool)

    fun decisionFailed(tool: String, detail: String): String = render(decisionFailed, TOOL to tool, DETAIL to detail)

    fun questionShown(tool: String): String = render(questionShown, TOOL to tool)

    fun questionAnswered(question: String, answer: String): String = render(questionAnswered, QUESTION to question, ANSWER to answer)

    fun capabilityMissing(tool: String, capability: String): String = render(capabilityMissing, TOOL to tool, CAPABILITY to capability)

    fun capabilityGranted(capability: String): String = render(capabilityGranted, CAPABILITY to capability)

    fun capabilityDenied(capability: String): String = render(capabilityDenied, CAPABILITY to capability)

    fun interruptedToolCall(tool: String): String = render(interruptedToolCall, TOOL to tool)

    fun toolFailed(tool: String, detail: String): String = render(toolFailed, TOOL to tool, DETAIL to detail)

    fun outputTruncated(limit: Int): String = render(outputTruncated, LIMIT to limit.toString())

    private fun render(template: String, vararg variables: Pair<String, String>): String =
        PromptTemplate(ConfigFiles.agentMessages, template).render(variables.toMap())
            .getOrElse { error("Unreachable: placeholders are validated in init (${it.message})") }

    private fun requirePlaceholders(template: String, vararg allowed: String) {
        val unknown = PromptTemplate(ConfigFiles.agentMessages, template).placeholders - allowed.toSet()
        require(unknown.isEmpty()) { "Unknown placeholders $unknown in agent message: $template" }
    }

    fun planUnfinished(steps: String): String = render(planUnfinished, STEPS to steps)

    fun outputSaved(head: String, tail: String, chars: Int, path: String): String =
        render(outputSaved, HEAD to head, TAIL to tail, CHARS to chars.toString(), PATH to path)


    fun memorySaved(id: String): String = render(memorySaved, ID to id)

    fun memoryTooLong(limit: Int): String = render(memoryTooLong, LIMIT to limit.toString())

    fun memoryFull(limit: Int): String = render(memoryFull, LIMIT to limit.toString())

    fun memoryNotFound(id: String): String = render(memoryNotFound, ID to id)

    /** The memory section of the system prompt: one line per remembered fact, as (id, text) pairs. */
    fun memories(items: List<Pair<String, String>>): String =
        if (items.isEmpty()) noMemories else items.joinToString("\n") { (id, text) -> render(memoryLine, ID to id, TEXT to text) }

    companion object {
        const val SCHEMA_VERSION = 9
        private const val TOOL = "tool"
        private const val DETAIL = "detail"
        private const val LIMIT = "limit"
        private const val OUTPUT = "output"
        private const val SOURCE = "source"
        private const val QUESTION = "question"
        private const val ANSWER = "answer"
        private const val CAPABILITY = "capability"
        private const val STEPS = "steps"
        private const val HEAD = "head"
        private const val TAIL = "tail"
        private const val CHARS = "chars"
        private const val PATH = "path"
        private const val ID = "id"
        private const val TEXT = "text"
    }
}
