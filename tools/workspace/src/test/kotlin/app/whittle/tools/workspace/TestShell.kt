package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import java.io.File

/**
 * The shell the tests run commands in, ended the way the phone ends them. On the phone every command gets its own
 * session and a stop kills the whole process group. Where `setsid` exists (Linux) the tests do the same; that matters
 * because Linux's /bin/sh (dash) runs a command as a child of the shell, so killing the shell alone leaves it running.
 * Without `setsid` (macOS, whose /bin/sh replaces itself with the command) the shell runs directly.
 */
internal fun testShellEnvironment(): ShellEnvironment {
    val setsid = executable(SETSID_PATHS)
    val kill = executable(KILL_PATHS)
    if (setsid == null || kill == null) return ShellEnvironment(shell = SHELL, path = PATH, launcher = null, killProcessGroup = null)
    return ShellEnvironment(
        shell = SHELL,
        path = PATH,
        launcher = CommandLauncher { _, _ -> Outcome.Success(listOf(setsid.path, SHELL, "-c")) },
        // The kill program, not dash's builtin, which doesn't take "--" before a negative (group) id.
        killProcessGroup = { group -> ProcessBuilder(kill.path, "-KILL", "--", "-$group").start().waitFor() },
    )
}

private const val SHELL = "/bin/sh"
private const val PATH = "/usr/bin:/bin"
private val SETSID_PATHS = listOf("/usr/bin/setsid", "/bin/setsid")
private val KILL_PATHS = listOf("/usr/bin/kill", "/bin/kill")

private fun executable(paths: List<String>): File? = paths.map(::File).firstOrNull { it.canExecute() }
