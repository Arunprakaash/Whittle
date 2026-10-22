package app.whittle.tools.workspace

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ProcessStatus { Starting, Running, Exited, Stopped }

/** A long-running process the agent started in a chat: a server or a watcher. */
data class ProcessInfo(
    val id: String,
    val workspace: WorkspaceId,
    val name: String,
    val command: String,
    /** Where it serves, once known: given when it was started, or seen in its output. */
    val port: Int?,
    val status: ProcessStatus,
    /** Something answers on [port]. */
    val listening: Boolean,
    val exitCode: Int?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
) {
    val live: Boolean get() = status == ProcessStatus.Starting || status == ProcessStatus.Running

    /** Where a browser on the device reaches it. */
    val url: String? get() = port?.let { "http://$LOOPBACK:$it/" }

    companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

/**
 * Starts commands that keep running after the tool call returns, the way every workspace command starts (see
 * [CommandStarter]), always with the network: a server needs sockets. Each keeps its latest output; a server counts as
 * listening once its port takes a connection. Processes end when stopped, when they exit, or with the app.
 */
class ProcessManager(
    environment: ShellEnvironment,
    private val limits: ProcessLimits,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether something on this device answers on a port. */
    private val listening: (Int) -> Boolean = ::answersOnLoopback,
) {
    private val starter = CommandStarter(environment)
    private val handles = ConcurrentHashMap<String, Handle>()
    private val mutableProcesses = MutableStateFlow<List<ProcessInfo>>(emptyList())

    /** Every process this run of the app knows, the ended ones until they're replaced or cleared. */
    val processes: StateFlow<List<ProcessInfo>> = mutableProcesses.asStateFlow()

    /**
     * Starts [command] in [directory]. One by the same [name] in the same chat is stopped first, so starting again
     * restarts it. Replies once the server answers on its port, the command ends, no port has shown up for a while,
     * or the wait runs out.
     */
    suspend fun start(workspace: WorkspaceId, directory: File, name: String, command: String, port: Int?): Outcome<ProcessInfo> {
        handles.values.filter { it.info.workspace == workspace && it.info.name == name }.forEach { previous ->
            stop(previous.info.id)
            forget(previous.info.id)
        }
        if (handles.values.count { it.info.workspace == workspace && it.info.live } >= limits.maxPerChat) {
            return Outcome.Failure(HarnessError.Configuration("This chat already runs ${limits.maxPerChat} processes; stop one first"))
        }
        val process = when (val started = starter.start(command, directory, network = true, mergeOutput = true)) {
            is Outcome.Failure -> return started
            is Outcome.Success -> started.value
        }
        val id = UUID.randomUUID().toString().take(ID_CHARS)
        val handle = Handle(
            process,
            ProcessInfo(id, workspace, name, command, port, ProcessStatus.Starting, listening = false, exitCode = null, startedAtMillis = clock(), endedAtMillis = null),
        )
        handles[id] = handle
        publish()
        scope.launch(Dispatchers.IO) { read(handle) }
        scope.launch(Dispatchers.IO) { watch(handle) }
        scope.launch(Dispatchers.IO) { probe(handle) }
        withTimeoutOrNull(limits.startWaitSeconds * MILLIS_PER_SECOND) {
            while (true) {
                val info = handle.info
                val quiet = info.port == null && clock() - info.startedAtMillis >= limits.noPortWaitSeconds * MILLIS_PER_SECOND
                if (!info.live || info.listening || quiet) break
                delay(PROBE_MILLIS)
            }
        }
        return Outcome.Success(handle.info)
    }

    fun info(id: String): ProcessInfo? = handles[id]?.info

    /** The latest [lines] lines of a process's output. */
    fun output(id: String, lines: Int = limits.readLines): String? = handles[id]?.let { handle ->
        synchronized(handle.output) { handle.output.toString() }.lines().dropLastWhile { it.isEmpty() }.takeLast(lines).joinToString("\n")
    }

    /** Stops it and everything it started; null when there's no such process. */
    suspend fun stop(id: String): ProcessInfo? {
        val handle = handles[id] ?: return null
        if (handle.info.live) {
            handle.stopRequested = true
            withContext(Dispatchers.IO + NonCancellable) {
                starter.kill(handle.process, handle.processGroup)
                withTimeoutOrNull(limits.stopGraceMillis) { mutableProcesses.first { list -> list.none { it.id == id && it.live } } }
            }
        }
        return handle.info
    }

    /** Stops every process in [workspace], or every process at all; for Stop all and deleted chats. */
    suspend fun stopAll(workspace: WorkspaceId? = null) {
        handles.values.filter { workspace == null || it.info.workspace == workspace }.forEach { stop(it.info.id) }
    }

    /** Leaves a chat's ended processes out from now on, e.g. when the chat is deleted. */
    fun clear(workspace: WorkspaceId) {
        handles.values.filter { it.info.workspace == workspace && !it.info.live }.forEach { forget(it.info.id) }
    }

    private fun forget(id: String) {
        handles.remove(id)
        publish()
    }

    /** The first line is the process group; the rest is output, the newest kept. A port in it is noted. */
    private fun read(handle: Handle) {
        try {
            handle.process.inputStream.bufferedReader().useLines { lines ->
                var first = true
                lines.forEach { line ->
                    if (first) {
                        first = false
                        handle.processGroup = line.trim().toIntOrNull()
                        return@forEach
                    }
                    synchronized(handle.output) {
                        handle.output.append(line).append('\n')
                        val excess = handle.output.length - limits.keptOutputChars
                        if (excess > 0) handle.output.delete(0, handle.output.indexOf("\n", excess).let { if (it < 0) excess else it + 1 })
                    }
                    if (handle.info.port == null) PORT.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { port -> update(handle) { it.copy(port = port) } }
                }
            }
        } catch (exception: IOException) {
            // Closed after a stop; what was read is the output.
        }
    }

    private fun watch(handle: Handle) {
        val code = handle.process.waitFor()
        update(handle) {
            it.copy(
                status = if (handle.stopRequested) ProcessStatus.Stopped else ProcessStatus.Exited,
                exitCode = if (handle.stopRequested) null else code,
                listening = false,
                endedAtMillis = clock(),
            )
        }
    }

    /** Running once it's been up a moment; listening once its port takes a connection. */
    private suspend fun probe(handle: Handle) {
        delay(SETTLE_MILLIS)
        if (handle.info.status == ProcessStatus.Starting) update(handle) { if (it.live) it.copy(status = ProcessStatus.Running) else it }
        while (handle.info.live && !handle.info.listening) {
            val port = handle.info.port
            if (port != null && listening(port)) update(handle) { if (it.live) it.copy(status = ProcessStatus.Running, listening = true) else it }
            delay(PROBE_MILLIS)
        }
    }

    private fun update(handle: Handle, change: (ProcessInfo) -> ProcessInfo) {
        synchronized(handle) { handle.info = change(handle.info) }
        publish()
    }

    private fun publish() = mutableProcesses.update { handles.values.map { it.info }.sortedBy { it.startedAtMillis } }

    private class Handle(val process: Process, @Volatile var info: ProcessInfo) {
        @Volatile var processGroup: Int? = null
        @Volatile var stopRequested = false
        val output = StringBuilder()
    }

    private companion object {
        const val ID_CHARS = 8
        const val MILLIS_PER_SECOND = 1_000L
        const val SETTLE_MILLIS = 1_000L
        const val PROBE_MILLIS = 300L
        const val CONNECT_TIMEOUT_MILLIS = 250
        /** A local address in a server's output: "http://localhost:5173/", "127.0.0.1:8000", "[::1]:3000". */
        val PORT = Regex("""(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?]):(\d{2,5})\b""")

        fun answersOnLoopback(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(ProcessInfo.LOOPBACK, port), CONNECT_TIMEOUT_MILLIS) }
            true
        } catch (exception: IOException) {
            false
        }
    }
}
