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
 * How commands run on this platform. On Android the shell is /system/bin/sh and each command gets its
 * own process group (setsid) so Stop and timeouts kill everything it started, not just the shell.
 */
data class ShellEnvironment(
    val shell: String,
    val path: String,
    /** Command that puts the shell in a new process group, or null where groups aren't available. */
    val processGroupLauncher: String?,
    /** Sends SIGKILL to a whole process group; null falls back to killing the shell alone. */
    val killProcessGroup: ((processGroupId: Int) -> Unit)?,
)
