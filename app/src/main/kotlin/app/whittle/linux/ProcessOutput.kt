package app.whittle.linux

import java.io.IOException

/**
 * A long-running Linux process's output, as [app.whittle.tools.workspace.CommandStarter] gives it: its process group
 * first, then its latest lines, the newest [keptChars] kept, to say why it stopped.
 */
internal class ProcessOutput(private val keptChars: Int) {
    @Volatile var processGroup: Int? = null
    private val text = StringBuilder()

    fun read(process: Process) = try {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (index == 0) {
                    processGroup = line.trim().toIntOrNull()
                } else {
                    synchronized(text) {
                        text.append(line).append('\n')
                        if (text.length > keptChars) text.delete(0, text.length - keptChars)
                    }
                }
            }
        }
    } catch (exception: IOException) {
        // Closed after a stop; what was read is the output.
    }

    fun lastLine(): String? = synchronized(text) { text.lines().lastOrNull { it.isNotBlank() }?.trim() }
}
