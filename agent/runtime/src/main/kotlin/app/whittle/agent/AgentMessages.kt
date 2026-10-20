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
    val interruptedToolCall: String,
    val toolFailed: String,
    val outputTruncated: String,
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
        requirePlaceholders(interruptedToolCall, TOOL)
        requirePlaceholders(toolFailed, TOOL, DETAIL)
        requirePlaceholders(outputTruncated, LIMIT)
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

    companion object {
        const val SCHEMA_VERSION = 2
        private const val TOOL = "tool"
        private const val DETAIL = "detail"
        private const val LIMIT = "limit"
        private const val OUTPUT = "output"
        private const val SOURCE = "source"
    }
}
