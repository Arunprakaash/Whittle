package app.whittle.tools.workspace

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
 * from the app's own environment), and a timeout or cancellation kills the command's whole process group.
 */
class CommandRunner(private val environment: ShellEnvironment, private val maxOutputChars: Int) {
    suspend fun run(command: String, directory: File, timeoutSeconds: Int): CommandResult = withContext(Dispatchers.IO) {
        val tmp = File(directory, TMP_DIRECTORY).apply { mkdirs() }
        // The outer shell prints its pid, then becomes the command (in a new process group when available).
        val launcher = environment.processGroupLauncher?.let { "$it " }.orEmpty()
        val process = ProcessBuilder(environment.shell, "-c", "echo $$; exec $launcher${environment.shell} -c \"\$$COMMAND_VARIABLE\"")
            .directory(directory)
            .apply {
                environment().clear()
                environment()[PATH] = this@CommandRunner.environment.path
                environment()[HOME] = directory.path
                environment()[TMPDIR] = tmp.path
                environment()[COMMAND_VARIABLE] = command
            }
            .start()
        process.outputStream.close()
        val startedAt = System.currentTimeMillis()
        var processGroup: Int? = null
        var finished = false
        try {
            coroutineScope {
                val stdout = async { process.inputStream.readCapped(firstLine = { processGroup = it.trim().toIntOrNull() }) }
                val stderr = async { process.errorStream.readCapped(firstLine = null) }
                val deadline = startedAt + TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong())
                while (process.isAlive && System.currentTimeMillis() < deadline) delay(POLL_MILLIS)
                val timedOut = process.isAlive
                if (timedOut) kill(process, processGroup)
                // A background job ("cmd &") can hold the output open after the shell exits; give it a moment, then end it.
                if (withTimeoutOrNull(OUTPUT_GRACE_MILLIS) { stdout.await(); stderr.await() } == null) kill(process, processGroup)
                val (out, outCut) = stdout.await()
                val (err, errCut) = stderr.await()
                finished = true
                CommandResult(
                    exitCode = if (timedOut) null else process.exitValue(),
                    stdout = out,
                    stderr = err,
                    timedOut = timedOut,
                    truncated = outCut || errCut,
                    durationMillis = System.currentTimeMillis() - startedAt,
                )
            }
        } finally {
            // Stop or a cancelled run: nothing the command started may keep running.
            if (!finished) withContext(NonCancellable) { kill(process, processGroup) }
        }
    }

    private fun kill(process: Process, processGroup: Int?) {
        val killGroup = environment.killProcessGroup
        if (killGroup != null && processGroup != null) killGroup(processGroup)
        process.destroyForcibly()
        // Grandchildren may hold the pipes open; closing our ends lets the readers finish.
        process.inputStream.close()
        process.errorStream.close()
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
        const val COMMAND_VARIABLE = "WHITTLE_COMMAND"
        const val PATH = "PATH"
        const val HOME = "HOME"
        const val TMPDIR = "TMPDIR"
        const val TMP_DIRECTORY = ".tmp"
        const val POLL_MILLIS = 50L
        const val OUTPUT_GRACE_MILLIS = 1_000L
    }
}
