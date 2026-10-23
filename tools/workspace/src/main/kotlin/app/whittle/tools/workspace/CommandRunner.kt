package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class CommandResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMillis: Long,
)

/**
 * Runs one shell command in a workspace. The environment is rebuilt from scratch (no keys or tokens
 * from the app's own environment), the platform's launcher sandboxes it (no network unless [network]),
 * and a timeout or cancellation kills the command's whole process group.
 */
class CommandRunner(private val environment: ShellEnvironment, private val maxOutputChars: Int) {
    private val starter = CommandStarter(environment)

    /** A failure when the platform can't run commands yet; a command that fails still succeeds here, with its exit code. */
    suspend fun run(command: String, directory: File, timeoutSeconds: Int, network: Boolean = false): Outcome<CommandResult> = withContext(Dispatchers.IO) {
        val process = when (val started = starter.start(command, directory, network, mergeOutput = false)) {
            is Outcome.Failure -> return@withContext started
            is Outcome.Success -> started.value
        }
        val startedAt = System.currentTimeMillis()
        var processGroup: Int? = null
        var finished = false
        try {
            coroutineScope {
                val stdout = async { process.inputStream.readCapped(firstLine = { processGroup = it.trim().toIntOrNull() }) }
                val stderr = async { process.errorStream.readCapped(firstLine = null) }
                val deadline = startedAt + TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong())
                try {
                    while (process.isAlive && System.currentTimeMillis() < deadline) delay(POLL_MILLIS)
                } catch (cancellation: CancellationException) {
                    // Kill first: the readers are blocked on the command's output and this scope waits for
                    // them, so cleaning up later would never happen while the command holds its pipes open.
                    withContext(NonCancellable) { starter.kill(process, processGroup) }
                    throw cancellation
                }
                val timedOut = process.isAlive
                if (timedOut) starter.kill(process, processGroup)
                // A background job ("cmd &") can hold the output open after the shell exits; give it a moment, then end it.
                if (withTimeoutOrNull(OUTPUT_GRACE_MILLIS) { stdout.await(); stderr.await() } == null) starter.kill(process, processGroup)
                val (out, outCut) = stdout.await()
                val (err, errCut) = stderr.await()
                finished = true
                Outcome.Success(
                    CommandResult(
                        exitCode = if (timedOut) null else process.exitValue(),
                        stdout = out,
                        stderr = err,
                        timedOut = timedOut,
                        truncated = outCut || errCut,
                        durationMillis = System.currentTimeMillis() - startedAt,
                    ),
                )
            }
        } finally {
            // Stop or a cancelled run: nothing the command started may keep running.
            if (!finished) withContext(NonCancellable) { starter.kill(process, processGroup) }
        }
    }

    /** Keeps the first [maxOutputChars] and drains the rest, so a chatty command never blocks on a full pipe. */
    private fun InputStream.readCapped(firstLine: ((String) -> Unit)?): Pair<String, Boolean> {
        val kept = StringBuilder()
        var truncated = false
        var awaitingFirstLine = firstLine != null
        try {
            bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (awaitingFirstLine) {
                        awaitingFirstLine = false
                        firstLine?.invoke(line)
                        return@forEach
                    }
                    if (kept.length < maxOutputChars) {
                        kept.append(line).append('\n')
                        if (kept.length > maxOutputChars) {
                            kept.setLength(maxOutputChars)
                            truncated = true
                        }
                    } else {
                        truncated = true
                    }
                }
            }
        } catch (exception: java.io.IOException) {
            // The stream was closed after a kill; what was read so far is the output.
        }
        return kept.toString() to truncated
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val OUTPUT_GRACE_MILLIS = 1_000L
    }
}

/**
 * Starts a command the way every workspace command starts: the platform's launcher (Linux, sandbox, its own
 * session), an environment rebuilt from scratch, and the outer shell's pid printed first, which is the command's
 * process group.
 */
class CommandStarter(private val environment: ShellEnvironment) {
    /**
     * [mergeOutput] sends stderr into stdout, for output read as one stream. [keepInput] leaves the command's input
     * open, for a program that reads what to do from it; otherwise it reads end-of-input at once.
     */
    suspend fun start(
        command: String,
        directory: File,
        network: Boolean,
        mergeOutput: Boolean,
        keepInput: Boolean = false,
    ): Outcome<Process> = withContext(Dispatchers.IO) {
        val start = environment.launcher?.command(directory, network) ?: Outcome.Success(listOf(environment.shell, SHELL_COMMAND))
        val commandLine = when (start) {
            is Outcome.Failure -> return@withContext start
            is Outcome.Success -> start.value.joinToString(separator = " ") { quoted(it) }
        }
        val tmp = File(directory, TMP_DIRECTORY).apply { mkdirs() }
        val tools = environment.tools.setup()
        val script = (tools.functions + command).joinToString("\n")
        // The outer shell prints its pid, then becomes the launcher (same pid), which starts the command's shell.
        val process = ProcessBuilder(environment.shell, SHELL_COMMAND, "echo $$; exec $commandLine \"\$$COMMAND_VARIABLE\"")
            .directory(directory)
            .redirectErrorStream(mergeOutput)
            .apply {
                environment().clear()
                environment()[PATH] = this@CommandStarter.environment.path
                environment()[HOME] = directory.path
                environment()[TMPDIR] = tmp.path
                environment().putAll(tools.environment)
                environment()[COMMAND_VARIABLE] = script
            }
            .start()
        if (!keepInput) process.outputStream.close()
        Outcome.Success(process)
    }

    /** Ends the command and everything it started; [processGroup] is the pid it printed first, once read. */
    fun kill(process: Process, processGroup: Int?) {
        val killGroup = environment.killProcessGroup
        if (killGroup != null && processGroup != null) killGroup(processGroup)
        process.destroyForcibly()
        // Grandchildren may hold the pipes open; closing our ends lets the readers finish.
        process.inputStream.close()
        process.errorStream.close()
    }

    /** Single-quoted for the outer shell, so paths and flags pass through unchanged. */
    private fun quoted(argument: String): String = "'" + argument.replace("'", "'\\''") + "'"

    private companion object {
        const val COMMAND_VARIABLE = "WHITTLE_COMMAND"
        const val SHELL_COMMAND = "-c"
        const val PATH = "PATH"
        const val HOME = "HOME"
        const val TMPDIR = "TMPDIR"
        const val TMP_DIRECTORY = ".tmp"
    }
}
