package app.whittle.claudecode

import app.whittle.connectors.mcp.McpAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException

/**
 * Files a Claude Code task needs that hold the user's secrets (its MCP config, the API key). They're written outside
 * the workspace, where the agent's file tools, Changes and the Files app would show them; only their paths go on the
 * command line, and each is removed when its task ends.
 */
class ClaudeCodeTaskFiles(
    private val config: ClaudeCodeConfig,
    /** The host file at a Linux path; null before Linux is set up. */
    private val linuxFile: (String) -> File?,
) {
    /** One task's file, by its Linux [path]. */
    class TaskFile(val path: String, private val file: File) {
        suspend fun remove() {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    /** Writes [text] as the file [name]; null when it can't be written. */
    suspend fun write(name: String, text: String): TaskFile? {
        val path = "${config.taskFilesDirectory.trimEnd('/')}/${safe(name)}"
        val file = linuxFile(path) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(text)
                TaskFile(path, file)
            } catch (exception: IOException) {
                file.delete()
                null
            }
        }
    }
}

/** The user's connections, handed to a Claude Code task as an MCP config file. */
class ClaudeCodeConnections(
    private val config: ClaudeCodeConfig,
    /** Every connected service, as another MCP client reaches it. */
    private val connections: suspend () -> List<McpAccess>,
    private val files: ClaudeCodeTaskFiles,
) {
    /**
     * A task's MCP config: its file, the tools the user switched off, and Whittle's tool ids by the prefix Claude Code
     * gives each connection's tools.
     */
    class Handed(val file: ClaudeCodeTaskFiles.TaskFile, val blocked: List<String>, private val prefixes: Map<String, String>) {
        /** Whittle's id for a connection tool Claude Code names [tool], so its step shows like Whittle's own; else [tool]. */
        fun toolId(tool: String): String =
            prefixes.entries.firstOrNull { tool.startsWith(it.key) }?.let { (claude, whittle) -> whittle + tool.removePrefix(claude) } ?: tool
    }

    /**
     * Writes the MCP config for task [name]; null when there are no connections or it can't be written, and the task
     * then runs without them rather than not at all.
     */
    suspend fun hand(name: String): Handed? {
        val servers = connections().ifEmpty { return null }
        val file = files.write("$MCP_PREFIX$name$JSON", ClaudeCodeMcp.config(servers, config)) ?: return null
        return Handed(file, ClaudeCodeMcp.blocked(servers, config), ClaudeCodeMcp.prefixes(servers, config))
    }

    private companion object {
        const val MCP_PREFIX = "mcp-"
        const val JSON = ".json"
    }
}

/** Builds Claude Code's MCP config; pure, so it's tested on the JVM. */
internal object ClaudeCodeMcp {
    /** `{"mcpServers": {name: {"type", "url", "headers"}}}`, one entry per connection. */
    fun config(servers: List<McpAccess>, config: ClaudeCodeConfig): String = buildJsonObject {
        putJsonObject(SERVERS) {
            servers.forEach { server ->
                putJsonObject(name(server)) {
                    put(TYPE, config.mcpTransports.getValue(server.transport))
                    put(URL, server.url)
                    if (server.headers.isNotEmpty()) putJsonObject(HEADERS) { server.headers.forEach { (key, value) -> put(key, value) } }
                }
            }
        }
    }.toString()

    /** The tools the user switched off, by Claude Code's names for them. */
    fun blocked(servers: List<McpAccess>, config: ClaudeCodeConfig): List<String> =
        servers.flatMap { server -> server.excludedTools.sorted().map { config.mcpTool(name(server), it) } }

    /** Whittle's tool prefix of each connection, by the prefix Claude Code gives its tools. */
    fun prefixes(servers: List<McpAccess>, config: ClaudeCodeConfig): Map<String, String> =
        servers.associate { server -> config.mcpTool(name(server), "") to server.toolPrefix }

    /** A connection's name for Claude Code: letters, digits, _ and - only. */
    fun name(server: McpAccess): String = safe(server.id)

    private const val SERVERS = "mcpServers"
    private const val TYPE = "type"
    private const val URL = "url"
    private const val HEADERS = "headers"
}

private val UNSAFE = Regex("[^A-Za-z0-9_-]")

/** Letters, digits, _ and - only, for a file or server name. */
private fun safe(name: String): String = name.replace(UNSAFE, "_")
