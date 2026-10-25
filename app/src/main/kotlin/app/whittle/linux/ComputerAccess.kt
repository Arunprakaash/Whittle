package app.whittle.linux

import app.whittle.core.common.Outcome
import app.whittle.tools.workspace.CommandResult
import app.whittle.tools.workspace.CommandRunner
import app.whittle.tools.workspace.CommandStarter
import app.whittle.tools.workspace.ShellEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Where using Linux from a computer stands. */
sealed interface ComputerAccessState {
    data object Off : ComputerAccessState

    /** Installing the SSH server, the first time it's turned on. */
    data object Installing : ComputerAccessState

    data object Starting : ComputerAccessState

    /** Computers on the same Wi-Fi can sign in at [address]. */
    data class On(val address: String) : ComputerAccessState

    /** It didn't turn on, or stopped; [detail] is the server's or installer's last line, when there is one. */
    data class Failed(val detail: String?) : ComputerAccessState
}

/**
 * Lets the user's own computers sign in to the Linux workspace over SSH. Off until the user turns it on; then OpenSSH
 * is installed once, and runs in Linux while the phone is on Wi-Fi, listening on its Wi-Fi address (and on the phone
 * itself), signing in only the keys in [ComputerAccessConfig.keysFile] and never with a password. It turns itself off
 * when the phone leaves Wi-Fi and moves with the address when that changes. An SSH session is the user's own shell:
 * the agent never uses it.
 */
class ComputerAccess(
    val config: ComputerAccessConfig,
    environment: ShellEnvironment,
    /** The host file at a Linux path; null before Linux is set up. */
    private val linuxFile: (String) -> File?,
    /** Where the server's commands start; null before Linux is set up. */
    private val folder: () -> File?,
    private val wifi: () -> StateFlow<String?>,
    private val scope: CoroutineScope,
    /** Told when the server starts and stops serving, to keep the app alive behind a notification. */
    private val serving: (Boolean) -> Unit,
) {
    private val commands = CommandRunner(environment, config.keptOutputChars)
    private val starter = CommandStarter(environment)
    private val keysLock = Mutex()
    private var job: Job? = null

    private val mutableState = MutableStateFlow<ComputerAccessState>(ComputerAccessState.Off)
    val state: StateFlow<ComputerAccessState> = mutableState.asStateFlow()

    private val mutableComputers = MutableStateFlow<List<ComputerKey>>(emptyList())

    /** The computers that may sign in, from the keys file once [load] has read it. */
    val computers: StateFlow<List<ComputerKey>> = mutableComputers.asStateFlow()

    /** The Wi-Fi address computers reach the phone at; null when it isn't on Wi-Fi. */
    val address: StateFlow<String?> get() = wifi()

    /** Reads the keys file, for Settings. */
    suspend fun load() = keysLock.withLock {
        mutableComputers.value = withContext(Dispatchers.IO) { keysFile()?.takeIf { it.isFile }?.readText()?.let(ComputerKeys::parse).orEmpty() }
    }

    /** Adds the pasted public key; null when it was added, otherwise why not. */
    suspend fun add(pasted: String): KeyProblem? = keysLock.withLock {
        when (val result = ComputerKeys.add(mutableComputers.value, pasted, config.keyTypes, config.maxComputers)) {
            is ComputerKeys.Result.Added -> if (save(result.keys)) null else KeyProblem.NOT_A_KEY
            is ComputerKeys.Result.Refused -> result.problem
        }
    }

    /** Stops [key] signing in, from the next sign-in on. */
    suspend fun remove(key: ComputerKey) = keysLock.withLock { save(mutableComputers.value.filterNot { it.sameKey(key) }) }

    /** Turns it on, unless it's on already or turning on. */
    fun turnOn() {
        if (job?.isActive == true) return
        job = scope.launch { serve() }
    }

    fun turnOff() {
        job?.cancel()
        job = null
        mutableState.value = ComputerAccessState.Off
    }

    private suspend fun serve() {
        var address = wifi().value ?: return
        val directory = folder() ?: return fail(null)
        if (!installed(directory)) {
            mutableState.value = ComputerAccessState.Installing
            val installed = commands.run(config.installCommand, directory, config.installTimeoutSeconds, network = true)
            if (installed !is Outcome.Success || installed.value.exitCode != 0) return fail((installed as? Outcome.Success)?.value?.let(::lastLine))
        }
        if (keysFile()?.let(::ensureExists) != true) return fail(null)
        // Serves until the server stops or Wi-Fi changes; a new address starts it again there.
        while (true) {
            address = when (val ended = serveAt(address, directory)) {
                is Ended.Moved -> ended.address ?: break
                is Ended.Stopped -> return fail(ended.detail)
            }
        }
        mutableState.value = ComputerAccessState.Off
    }

    /** Runs the server at [address] until it stops by itself or Wi-Fi changes. */
    private suspend fun serveAt(address: String, directory: File): Ended = coroutineScope {
        mutableState.value = ComputerAccessState.Starting
        val process = when (val started = starter.start(config.serve(address), directory, network = true, mergeOutput = true)) {
            is Outcome.Failure -> return@coroutineScope Ended.Stopped(started.error.message)
            is Outcome.Success -> started.value
        }
        val output = Output(config.keptOutputChars)
        val reader = launch(Dispatchers.IO) { output.read(process) }
        try {
            val up = withTimeoutOrNull(config.startWaitSeconds * MILLIS_PER_SECOND) {
                while (process.isAlive && !answers(config.port)) delay(PROBE_MILLIS)
                process.isAlive
            }
            if (up != true) return@coroutineScope Ended.Stopped(output.lastLine())
            mutableState.value = ComputerAccessState.On(address)
            serving(true)
            val exited = async(Dispatchers.IO) { runInterruptible { process.waitFor() } }
            val moved = async { wifi().first { it != address } }
            select<Ended> {
                exited.onAwait { moved.cancel(); Ended.Stopped(output.lastLine()) }
                moved.onAwait { next -> exited.cancel(); Ended.Moved(next) }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                starter.kill(process, output.processGroup)
                serving(false)
            }
            reader.cancel()
        }
    }

    private fun fail(detail: String?) {
        mutableState.value = ComputerAccessState.Failed(detail?.takeIf { it.isNotBlank() })
    }

    private suspend fun installed(directory: File): Boolean =
        (commands.run(config.checkCommand, directory, CHECK_TIMEOUT_SECONDS) as? Outcome.Success)?.value?.exitCode == 0

    private fun keysFile(): File? = linuxFile(config.keysFile)

    private fun ensureExists(file: File): Boolean = try {
        file.parentFile?.mkdirs()
        file.exists() || file.createNewFile()
    } catch (exception: IOException) {
        false
    }

    private suspend fun save(keys: List<ComputerKey>): Boolean = withContext(Dispatchers.IO) {
        val file = keysFile() ?: return@withContext false
        try {
            file.parentFile?.mkdirs()
            file.writeText(ComputerKeys.write(keys))
            mutableComputers.value = keys
            true
        } catch (exception: IOException) {
            false
        }
    }

    private fun lastLine(result: CommandResult): String? =
        result.stderr.ifBlank { result.stdout }.lines().lastOrNull { it.isNotBlank() }?.trim()

    /** How serving at one address ended: Wi-Fi moved to [address] (null when it's gone), or the server stopped. */
    private sealed interface Ended {
        data class Moved(val address: String?) : Ended

        data class Stopped(val detail: String?) : Ended
    }

    /** The server's output: its process group first (see [CommandStarter]), then its latest lines. */
    private class Output(private val keptChars: Int) {
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

    private companion object {
        const val CHECK_TIMEOUT_SECONDS = 30
        const val MILLIS_PER_SECOND = 1_000L
        const val PROBE_MILLIS = 300L
        const val CONNECT_TIMEOUT_MILLIS = 250
        const val LOOPBACK = "127.0.0.1"

        fun answers(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MILLIS) }
            true
        } catch (exception: IOException) {
            false
        }
    }
}
