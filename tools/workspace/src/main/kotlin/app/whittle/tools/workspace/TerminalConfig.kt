package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable
import java.io.File

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
 * How commands run on this platform. [shell] starts each command's launcher in its own process group (so Stop and
 * timeouts kill everything it started); on Android the launcher runs the command inside Linux, behind Whittle's
 * seccomp sandbox.
 */
data class ShellEnvironment(
    val shell: String,
    val path: String,
    /** Starts the command's shell (Linux, sandbox); null runs it with [shell] directly, e.g. on a desktop for tests. */
    val launcher: CommandLauncher?,
    /** Sends SIGKILL to a whole process group; null falls back to killing the shell alone. */
    val killProcessGroup: ((processGroupId: Int) -> Unit)?,
    /** What the launcher's own programs need, set up before each command. */
    val tools: ShellTools = ShellTools.NONE,
)

/** How a command's shell is started. */
fun interface CommandLauncher {
    /**
     * The program and arguments that run a command for [workspace], ending with the shell and its `-c`; the
     * command's text follows. [network] lets the command open network connections; without it, it can't open any
     * socket. A failure when commands can't run here yet, e.g. before Linux is set up.
     */
    fun command(workspace: File, network: Boolean): Outcome<List<String>>
}

/** What the platform's own programs need before each command, e.g. the files that start Linux. */
fun interface ShellTools {
    /** What every command gets; [ShellSetup.NONE] when there's nothing to set up, or it couldn't be. */
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
