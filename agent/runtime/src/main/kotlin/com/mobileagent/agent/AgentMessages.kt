package com.mobileagent.agent

import com.mobileagent.core.common.getOrElse
import com.mobileagent.core.config.ConfigFiles
import com.mobileagent.core.config.PromptTemplate
import com.mobileagent.core.config.VersionedConfig
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
    val userDeclined: String,
    val interruptedToolCall: String,
    val toolFailed: String,
    val outputTruncated: String,
) : VersionedConfig {
    init {
        requirePlaceholders(unknownTool, TOOL)
        requirePlaceholders(invalidArguments, TOOL, DETAIL)
        requirePlaceholders(policyDenied, TOOL)
        requirePlaceholders(userDeclined, TOOL)
        requirePlaceholders(interruptedToolCall, TOOL)
        requirePlaceholders(toolFailed, TOOL, DETAIL)
        requirePlaceholders(outputTruncated, LIMIT)
    }

    fun unknownTool(tool: String): String = render(unknownTool, TOOL to tool)

    fun invalidArguments(tool: String, detail: String): String = render(invalidArguments, TOOL to tool, DETAIL to detail)

    fun policyDenied(tool: String): String = render(policyDenied, TOOL to tool)

    fun userDeclined(tool: String): String = render(userDeclined, TOOL to tool)

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
        const val SCHEMA_VERSION = 1
        private const val TOOL = "tool"
        private const val DETAIL = "detail"
        private const val LIMIT = "limit"
    }
}
