package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * A command that keeps talking: its output (stdout and stderr together) arrives line by line while lines are written
 * to its input, as with a program that reads requests from stdin and answers on stdout, or a sign-in that waits for a
 * code. It runs in the same sandbox as any other command.
 */
class InteractiveCommand private constructor(private val process: Process, private val starter: CommandStarter) {
    @Volatile
    private var processGroup: Int? = null
    private val input = process.outputStream.bufferedWriter()

    /** The output, one line at a time, until the command ends. Collect it once. */
    val lines: Flow<String> = flow {
        val reader = process.inputStream.bufferedReader()
        // The launcher's first line is the process group, used to stop everything the command started.
        processGroup = reader.readLine()?.trim()?.toIntOrNull()
        while (true) emit(reader.readLine() ?: break)
    }.flowOn(Dispatchers.IO)

    /** Sends [line] to the command's input; false once the command no longer reads it. */
    suspend fun write(line: String): Boolean = withContext(Dispatchers.IO) {
        try {
            input.write(line)
            input.newLine()
            input.flush()
            true
        } catch (exception: IOException) {
            false
        }
    }

    /** Tells the command there's nothing more to read. */
    suspend fun closeInput() = withContext(Dispatchers.IO) {
        try {
            input.close()
        } catch (exception: IOException) {
            // Already closed by the command ending.
        }
    }

    /** Waits for the command to end. */
    suspend fun exitCode(): Int = withContext(Dispatchers.IO) { process.waitFor() }

    /** Ends the command and everything it started. */
    fun stop() = starter.kill(process, processGroup)

    companion object {
        /** Starts [command] in [directory]; a failure when commands can't run here yet, e.g. before Linux is set up. */
        suspend fun start(environment: ShellEnvironment, command: String, directory: File, network: Boolean): Outcome<InteractiveCommand> {
            val starter = CommandStarter(environment)
            return when (val started = starter.start(command, directory, network, mergeOutput = true, keepInput = true)) {
                is Outcome.Failure -> started
                is Outcome.Success -> Outcome.Success(InteractiveCommand(started.value, starter))
            }
        }
    }
}
