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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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

    /** Computers can sign in: on the same Wi-Fi at [wifi], and from anywhere over Tailscale at [tailscale]. */
    data class On(val wifi: String?, val tailscale: String?) : ComputerAccessState {
        /** Where a computer signs in, the Wi-Fi address first. */
        val address: String get() = wifi ?: tailscale.orEmpty()
    }

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
    /** Reaching the phone from anywhere, when the user set it up. */
    val tailscale: Tailscale,
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

    /** Where computers can reach the phone right now: its Wi-Fi address, its tailnet address, either or neither. */
    private val networks: StateFlow<Networks> by lazy {
        combine(wifi(), tailscale.address, ::Networks).stateIn(scope, SharingStarted.Eagerly, Networks(wifi().value, tailscale.address.value))
    }

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

    /**
     * Signs in to Tailscale. While access is on, the tailnet joins as soon as the user has signed in; while it's off,
     * it turns on once connected, as signing in is asking to be reached that way.
     */
    fun signInToTailscale() {
        tailscale.signIn()
        if (job?.isActive == true) return
        scope.launch {
            val ended = tailscale.state.first { it is TailscaleState.Connected || it is TailscaleState.Failed || it is TailscaleState.SignedOut }
            if (ended is TailscaleState.Connected) turnOn()
        }
    }

    fun turnOff() {
        job?.cancel()
        job = null
        mutableState.value = ComputerAccessState.Off
        scope.launch { tailscale.stop() }
    }

    private suspend fun serve() {
        // Turning on shows at once: Tailscale and the server can take a few seconds.
        mutableState.value = ComputerAccessState.Starting
        val directory = folder() ?: return fail(null)
        // Tailscale first, when it's signed in: away from Wi-Fi it's the only way in. Its address reaches [networks] a
        // moment after it connects, so this waits for a way in rather than looking once.
        if (tailscale.signedIn) tailscale.start()
        if (withTimeoutOrNull(config.tailscale.connectWaitSeconds * MILLIS_PER_SECOND) { networks.first { !it.none } } == null) {
            // Tailscale's row says why it didn't connect; the switch goes back off.
            tailscale.timedOut()
            mutableState.value = ComputerAccessState.Off
            return
        }
        if (!installed(directory)) {
            mutableState.value = ComputerAccessState.Installing
            val installed = commands.run(config.installCommand, directory, config.installTimeoutSeconds, network = true)
            if (installed !is Outcome.Success || installed.value.exitCode != 0) return fail((installed as? Outcome.Success)?.value?.let(::lastLine))
        }
        if (keysFile()?.let(::ensureExists) != true) return fail(null)
        // Serves until the server stops or no way in is left; a new Wi-Fi address, or none, starts it again there.
        while (!networks.value.none) {
            when (val ended = serveAt(networks.value.wifi, directory)) {
                Ended.Moved -> continue
                is Ended.Stopped -> {
                    tailscale.stop()
                    return fail(ended.detail)
                }
            }
        }
        finish()
    }

    /** Off, with Tailscale stopped too, once neither Wi-Fi nor Tailscale is there. */
    private suspend fun finish() {
        tailscale.stop()
        mutableState.value = ComputerAccessState.Off
    }

    /** Runs the server on the phone and at [wifi] until it stops by itself, Wi-Fi changes, or no way in is left. */
    private suspend fun serveAt(wifi: String?, directory: File): Ended = coroutineScope {
        mutableState.value = ComputerAccessState.Starting
        val process = when (val started = starter.start(config.serve(wifi), directory, network = true, mergeOutput = true)) {
            is Outcome.Failure -> return@coroutineScope Ended.Stopped(started.error.message)
            is Outcome.Success -> started.value
        }
        val output = ProcessOutput(config.keptOutputChars)
        val reader = launch(Dispatchers.IO) { output.read(process) }
        try {
            val up = withTimeoutOrNull(config.startWaitSeconds * MILLIS_PER_SECOND) {
                while (process.isAlive && !answers(config.port)) delay(PROBE_MILLIS)
                process.isAlive
            }
            if (up != true) return@coroutineScope Ended.Stopped(output.lastLine())
            // On before the notification's service starts, as it reads this to show how to sign in.
            mutableState.value = ComputerAccessState.On(wifi, networks.value.tailscale)
            serving(true)
            // Tailscale coming and going shows here; it needs no restart, as it reaches the phone's loopback.
            val shown = launch { networks.collect { mutableState.value = ComputerAccessState.On(wifi, it.tailscale) } }
            val exited = async(Dispatchers.IO) { runInterruptible { process.waitFor() } }
            val moved = async { networks.first { it.wifi != wifi || it.none } }
            select<Ended> {
                exited.onAwait { moved.cancel(); Ended.Stopped(output.lastLine()) }
                moved.onAwait { exited.cancel(); Ended.Moved }
            }.also { shown.cancel() }
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

    /** How serving ended: the ways in changed, or the server stopped. */
    private sealed interface Ended {
        data object Moved : Ended

        data class Stopped(val detail: String?) : Ended
    }

    /** The phone's Wi-Fi and tailnet addresses, each null when it's not there. */
    private data class Networks(val wifi: String?, val tailscale: String?) {
        val none: Boolean get() = wifi == null && tailscale == null
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
