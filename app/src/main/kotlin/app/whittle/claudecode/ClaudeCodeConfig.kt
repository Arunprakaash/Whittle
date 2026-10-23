package app.whittle.claudecode

import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import app.whittle.core.config.VersionedConfig
import app.whittle.connectors.mcp.McpTransportKind
import kotlinx.serialization.Serializable

/**
 * Claude Code as a way to bring a model: Whittle installs it unchanged from Anthropic's own repository into Linux,
 * the user signs in on its own screen with their Claude plan, and Whittle runs it in print mode and shows its work.
 * Whittle never reads, stores or forwards that sign-in. [enabled] false hides it everywhere.
 */
@Serializable
data class ClaudeCodeConfig(
    override val schemaVersion: Int,
    val enabled: Boolean,
    /** Models chosen through Claude Code carry this provider id, so the chat runs them with Claude Code. */
    val providerId: String,
    val displayName: String,
    /** Under the app's files folder: which Claude Code session each chat carries on. */
    val sessionsFileName: String,
    /** Under the app's files folder: when Claude Code was last updated. */
    val updatedStampFileName: String,
    /** Run as root in Linux, with the network. */
    val installCommand: String,
    val removeCommand: String,
    /** Upgrades Claude Code from its own repository only, so nothing else in Linux changes. */
    val updateCommand: String,
    /** Exits with 0 when Claude Code is installed. */
    val versionCommand: String,
    /** Prints the sign-in status as JSON; exits with 0 when signed in. */
    val statusCommand: String,
    /** Claude Code's own sign-in: it prints a link and, when the browser can't come back to it, asks for a code. */
    val signInCommand: String,
    val signOutCommand: String,
    /** Print mode, reading and writing JSON lines; `{{model}}` is the model's id. */
    val runCommand: String,
    /** Added to [runCommand] to carry on a chat's conversation; `{{session}}` is Claude Code's session id. */
    val resumeArguments: String,
    /**
     * Added with the Claude Code tools an automation lets run without asking; `{{tools}}` is their list. Anything
     * else still asks, and the approval reaches the user as a notification, as with Whittle's own automations.
     */
    val allowedToolsArguments: String,
    /** Whittle's tool ids an automation can pre-approve, by the Claude Code tools that do the same. */
    val preApprovedTools: Map<String, List<String>>,
    /** Claude Code's tools that write a file, by the argument naming it, so its changes show in Files. */
    val fileArguments: Map<String, String>,
    /** Hands Claude Code the user's connections; `{{file}}` is the Linux path of their MCP config, made per task. */
    val mcpConfigArguments: String,
    /** Keeps tools from Claude Code, such as connection tools the user switched off; `{{tools}}` is their list. */
    val disallowedToolsArguments: String,
    /** What Claude Code calls a connection's tool: `{{server}}` is the connection's id, `{{tool}}` the server's name for it. */
    val mcpToolName: String,
    /** Claude Code's name for each way a connection talks MCP. */
    val mcpTransports: Map<McpTransportKind, String>,
    /** The Linux folder for a task's MCP config, outside the workspace; each file is removed when its task ends. */
    val mcpConfigDirectory: String,
    val installTimeoutSeconds: Int,
    /** How often Claude Code is updated quietly at launch, as its own installer keeps it current. */
    val updateEveryHours: Int,
    val commandTimeoutSeconds: Int,
    /** How often a running task looks for the user's answers and added messages. */
    val inboxPollMillis: Long,
    /** Claude Code's error kinds that mean its sign-in is gone or not allowed: the user signs in again. */
    val signedOutErrors: Set<String>,
    /** Error kinds that mean the Claude plan is used up for now. */
    val limitErrors: Set<String>,
    /** Claude Code's usage windows by its name for them, as the notice says them ("your 5-hour limit"). */
    val limitNames: Map<String, String>,
    /** Claude plans by Claude Code's name for them, as the picker shows them. */
    val planNames: Map<String, String>,
    /** What Claude Code is told when the user declines a step on an approval card. */
    val declinedMessage: String,
    /** How much of a setup command's output is kept. */
    val outputChars: Int,
    val models: List<ClaudeCodeModel>,
) : VersionedConfig {
    init {
        require(models.isNotEmpty()) { "claude-code.json needs at least one model" }
        require(PromptTemplate(FILE, runCommand).placeholders == setOf(MODEL)) { "runCommand must take {{$MODEL}} and nothing else" }
        require(PromptTemplate(FILE, resumeArguments).placeholders == setOf(SESSION)) { "resumeArguments must take {{$SESSION}} and nothing else" }
        require(PromptTemplate(FILE, allowedToolsArguments).placeholders == setOf(TOOLS)) { "allowedToolsArguments must take {{$TOOLS}} and nothing else" }
        require(PromptTemplate(FILE, mcpConfigArguments).placeholders == setOf(FILE_PATH)) { "mcpConfigArguments must take {{$FILE_PATH}} and nothing else" }
        require(PromptTemplate(FILE, disallowedToolsArguments).placeholders == setOf(TOOLS)) { "disallowedToolsArguments must take {{$TOOLS}} and nothing else" }
        require(PromptTemplate(FILE, mcpToolName).placeholders == setOf(SERVER, TOOL)) { "mcpToolName must take {{$SERVER}} and {{$TOOL}}" }
        require(mcpTransports.keys == McpTransportKind.entries.toSet()) { "mcpTransports must name every transport" }
        require(mcpConfigDirectory.startsWith("/")) { "mcpConfigDirectory must be an absolute Linux path" }
        require(updateEveryHours > 0) { "updateEveryHours must be positive" }
        require(installTimeoutSeconds > 0 && commandTimeoutSeconds > 0 && inboxPollMillis > 0 && outputChars > 0) { "claude-code.json limits must be positive" }
    }

    /**
     * The command that runs [model], carrying on [session] when the chat has one. The Claude Code tools that match
     * [preApproved] (Whittle's tool ids, from an automation) run without asking. [mcpConfig] is the Linux path of
     * the task's MCP config, when the user has connections; the [blocked] tools are kept from Claude Code.
     */
    fun run(
        model: String,
        session: String?,
        preApproved: Set<String> = emptySet(),
        mcpConfig: String? = null,
        blocked: List<String> = emptyList(),
    ): String {
        val parts = mutableListOf(runCommand.replace(placeholder(MODEL), quoted(model)))
        session?.let { parts += resumeArguments.replace(placeholder(SESSION), quoted(it)) }
        val tools = preApproved.flatMap { preApprovedTools[it].orEmpty() }.distinct()
        if (tools.isNotEmpty()) parts += allowedToolsArguments.replace(placeholder(TOOLS), quoted(tools.joinToString(TOOL_SEPARATOR)))
        mcpConfig?.let { parts += mcpConfigArguments.replace(placeholder(FILE_PATH), quoted(it)) }
        if (blocked.isNotEmpty()) parts += disallowedToolsArguments.replace(placeholder(TOOLS), quoted(blocked.joinToString(TOOL_SEPARATOR)))
        return parts.joinToString(" ")
    }

    /** What Claude Code calls [tool] of the connection whose Claude Code name is [server]. */
    fun mcpTool(server: String, tool: String): String = mcpToolName.replace(placeholder(SERVER), server).replace(placeholder(TOOL), tool)

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("claude-code.json")
        private const val MODEL = "model"
        private const val SESSION = "session"
        private const val TOOLS = "tools"
        private const val FILE_PATH = "file"
        private const val SERVER = "server"
        private const val TOOL = "tool"
        private const val TOOL_SEPARATOR = ","

        private fun placeholder(name: String) = "{{$name}}"

        /** Single-quoted for the shell, so an id can't add arguments. */
        private fun quoted(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}

/** A model Claude Code offers, by the alias it takes on its command line. */
@Serializable
data class ClaudeCodeModel(val id: String, val name: String, val detail: String)
