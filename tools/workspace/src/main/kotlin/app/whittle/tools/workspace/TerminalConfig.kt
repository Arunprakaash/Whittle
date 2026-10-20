package app.whittle.tools.workspace

import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable

/** Limits for the workspace tools. Platform details (which shell, how to kill) come from [ShellEnvironment]. */
@Serializable
data class TerminalConfig(
    override val schemaVersion: Int,
    val defaultTimeoutSeconds: Int,
    val maxTimeoutSeconds: Int,
    /** Per stream; the model sees the start of the output and a note that the rest was cut. */
    val maxOutputChars: Int,
    val maxFileChars: Int,
    val maxListedEntries: Int,
) : VersionedConfig {
    init {
        require(defaultTimeoutSeconds in 1..maxTimeoutSeconds) { "defaultTimeoutSeconds must be between 1 and maxTimeoutSeconds" }
        require(maxOutputChars > 0 && maxFileChars > 0 && maxListedEntries > 0) { "Terminal limits must be positive" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("terminal.json")
    }
}

/**
 * How commands run on this platform. On Android the shell is /system/bin/sh, started through Whittle's
 * sandbox: its own process group (so Stop and timeouts kill everything it started) behind a seccomp filter.
 */
data class ShellEnvironment(
    val shell: String,
    val path: String,
    /** Starts the shell (sandbox, process group); null runs it directly, e.g. on a desktop for tests. */
    val launcher: CommandLauncher?,
    /** Sends SIGKILL to a whole process group; null falls back to killing the shell alone. */
    val killProcessGroup: ((processGroupId: Int) -> Unit)?,
    /** Extra command-line tools this platform ships (Node, Python, git...), set up before the first command. */
    val tools: ShellTools = ShellTools.NONE,
)

/** The program and arguments a command's shell is started through. */
fun interface CommandLauncher {
    /** [network] lets the command open network connections; without it, it can't open any socket. */
    fun prefix(network: Boolean): List<String>
}

/** Command-line tools beyond the platform's own shell, installed on first use. */
fun interface ShellTools {
    /**
     * What every command gets; [ShellSetup.NONE] when there are no extra tools, or they couldn't be set up.
     * Commands still run with the platform's own shell then.
     */
    suspend fun setup(): ShellSetup

    companion object {
        val NONE = ShellTools { ShellSetup.NONE }
    }
}

data class ShellSetup(
    /** Added on top of PATH, HOME and TMPDIR (and may replace PATH). */
    val environment: Map<String, String>,
    /** Shell function definitions run before each command, e.g. tools started through their interpreter. */
    val functions: List<String>,
) {
    companion object {
        val NONE = ShellSetup(emptyMap(), emptyList())
    }
}
