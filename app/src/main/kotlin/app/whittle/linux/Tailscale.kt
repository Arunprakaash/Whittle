package app.whittle.linux

import app.whittle.core.common.Outcome
import app.whittle.tools.workspace.CommandRunner
import app.whittle.tools.workspace.CommandStarter
import app.whittle.tools.workspace.ShellEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** Where Tailscale stands on this phone. */
sealed interface TailscaleState {
    data object Checking : TailscaleState

    data object NotInstalled : TailscaleState

    data object Installing : TailscaleState

    /** Installed, and not signed in to a tailnet. */
    data object SignedOut : TailscaleState

    /** Signed in as [name], last seen at [ip]; not running, as computer access is off. */
    data class SignedIn(val name: String, val ip: String?) : TailscaleState

    data object Connecting : TailscaleState

    data object SigningOut : TailscaleState

    data object Removing : TailscaleState

    /** Waiting for the user to sign in at [url], in their browser. */
    data class WaitingForSignIn(val url: String) : TailscaleState

    /** On the tailnet as [name], at [ip]. */
    data class Connected(val name: String, val ip: String) : TailscaleState

    /** Setting up, connecting or signing out failed; [detail] is the last line said, when there is one. */
    data class Failed(val detail: String?) : TailscaleState
}

/**
 * Tailscale run inside Linux, so the user's own computers reach the phone from anywhere: the daemon runs in userspace
 * networking (no VPN, no root) while computer access is on, and hands what comes over the tailnet to the phone's
 * loopback, where the SSH server listens. Installed once from Tailscale's own repository; the user signs in with their
 * own account, in their browser, through the link it gives. Whittle never sees that sign-in.
 */
class Tailscale(
    private val config: TailscaleConfig,
    environment: ShellEnvironment,
    /** The host file at a Linux path; null before Linux is set up. */
    private val linuxFile: (String) -> File?,
    /** Where its commands start; null before Linux is set up. */
    private val folder: () -> File?,
    /** The phone's model, for its name on the tailnet. */
    private val model: String,
    private val scope: CoroutineScope,
) {
    private val commands = CommandRunner(environment, config.keptOutputChars)

    /** For the status JSON, which a short output limit would cut off mid-object. */
    private val statusReader = CommandRunner(environment, config.statusMaxChars)
    private val starter = CommandStarter(environment)
    private val lock = Mutex()
    private var daemon: Running? = null
    private var signIn: Running? = null
    private var watcher: Job? = null

    private val mutableState = MutableStateFlow<TailscaleState>(TailscaleState.Checking)
    val state: StateFlow<TailscaleState> = mutableState.asStateFlow()

    /** The phone's tailnet address while connected; null otherwise. */
    val address: StateFlow<String?> = state.map { (it as? TailscaleState.Connected)?.ip }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Whether it's signed in, running or not: computer access can then turn on away from Wi-Fi. */
    val signedIn: Boolean
        get() = when (state.value) {
            is TailscaleState.SignedIn, is TailscaleState.Connected, TailscaleState.Connecting -> true
            else -> false
        }

    /** Reads whether it's installed and signed in, for Settings; the daemon stays as it is. */
    suspend fun check() = lock.withLock {
        if (daemon != null) return@withLock
        mutableState.value = idleState()
    }

    /** Installs it from Tailscale's repository, the first time. */
    fun install() {
        scope.launch {
            lock.withLock {
                val directory = folder() ?: return@withLock fail(null)
                mutableState.value = TailscaleState.Installing
                val ran = commands.run(config.installCommand, directory, config.installTimeoutSeconds, network = true)
                val result = (ran as? Outcome.Success)?.value
                if (result?.exitCode != 0) return@withLock fail(result?.let { lastLine(it.stderr.ifBlank { it.stdout }) })
                mutableState.value = TailscaleState.SignedOut
            }
        }
    }

    /**
     * Starts the daemon, if it's signed in, so the tailnet reaches the phone; for computer access turning on. Returns
     * once it's connected, or once [TailscaleConfig.connectWaitSeconds] pass.
     */
    suspend fun start() {
        lock.withLock {
            // Running already, from a sign-in: it carries on as it is.
            if (daemon?.alive == true) return
            if (idleState() !is TailscaleState.SignedIn) return
            if (!startDaemon()) return
            mutableState.value = TailscaleState.Connecting
            watch()
        }
        withTimeoutOrNull(config.connectWaitSeconds * MILLIS_PER_SECOND) { state.first { it !is TailscaleState.Connecting } }
    }

    /** Signs in: starts the daemon if it isn't running, and shows the link Tailscale gives until the user has used it. */
    fun signIn() {
        scope.launch {
            // Shown at once: starting the daemon takes a few seconds.
            mutableState.value = TailscaleState.Connecting
            lock.withLock {
                val directory = folder() ?: return@withLock fail(null)
                if (!startDaemon()) return@withLock
                signIn?.stop()
                signIn = when (val started = starter.start(config.signIn(config.hostname(model)), directory, network = true, mergeOutput = true)) {
                    is Outcome.Failure -> return@withLock fail(started.error.message)
                    is Outcome.Success -> Running(started.value, config.keptOutputChars, scope)
                }
                watch()
            }
        }
    }

    /** Gives up on a start that didn't connect in time: stops the daemon and says what it said last. */
    suspend fun timedOut() = lock.withLock {
        if (state.value != TailscaleState.Connecting) return@withLock
        val detail = daemon?.output?.lastLine()
        stopDaemon()
        fail(detail)
    }

    /** Stops the daemon, signed in or not; for computer access turning off. */
    suspend fun stop() = lock.withLock {
        stopDaemon()
        mutableState.value = idleState()
    }

    /** Signs the phone out of the tailnet; with [remove], uninstalls Tailscale too. Stops the daemon either way. */
    fun signOut(remove: Boolean) {
        mutableState.value = TailscaleState.SigningOut
        scope.launch {
            lock.withLock {
                val directory = folder() ?: return@withLock fail(null)
                if (startDaemon()) {
                    commands.run(config.signOut(), directory, config.commandTimeoutSeconds, network = true)
                    // The daemon saves the signed-out state after logout returns; stopped before that, it starts signed in.
                    withTimeoutOrNull(config.commandTimeoutSeconds * MILLIS_PER_SECOND) {
                        while (status()?.needsLogin != true) delay(config.pollMillis)
                    }
                }
                stopDaemon()
                linuxFile(config.markerFile)?.delete()
                if (remove) {
                    mutableState.value = TailscaleState.Removing
                    commands.run(config.removeCommand, directory, config.installTimeoutSeconds, network = false)
                }
                mutableState.value = idleState()
            }
        }
    }

    /** Asks the daemon how it stands until it's stopped: connected, waiting for sign-in, or still connecting. */
    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive) {
                val status = status()
                val current = daemon
                when {
                    current == null -> return@launch
                    !current.alive -> {
                        lock.withLock { stopDaemon() }
                        return@launch fail(current.output.lastLine())
                    }
                    status?.running == true && status.ipv4 != null -> {
                        val name = status.name ?: config.hostname(model)
                        remember(name, status.ipv4)
                        signIn?.stop()
                        signIn = null
                        mutableState.value = TailscaleState.Connected(name, status.ipv4)
                    }
                    status?.authUrl != null -> mutableState.value = TailscaleState.WaitingForSignIn(status.authUrl)
                    // Signed out elsewhere, or its key expired, while nobody is signing in.
                    status?.needsLogin == true && signIn == null -> {
                        linuxFile(config.markerFile)?.delete()
                        mutableState.value = TailscaleState.SignedOut
                    }
                }
                delay(if (state.value is TailscaleState.Connected) config.connectedPollSeconds * MILLIS_PER_SECOND else config.pollMillis)
            }
        }
    }

    private suspend fun status(): TailscaleStatus? {
        val directory = folder() ?: return null
        // The CLI talks to the daemon over a socket, which Linux's sandbox allows only with the network.
        val ran = statusReader.run(config.status(), directory, config.commandTimeoutSeconds, network = true) as? Outcome.Success ?: return null
        return TailscaleStatus.parse(ran.value.stdout)
    }

    /** Starts the daemon unless it runs already; false, with the state saying why, when it can't. */
    private suspend fun startDaemon(): Boolean {
        if (daemon?.alive == true) return true
        val directory = folder() ?: return false.also { fail(null) }
        daemon = when (val started = starter.start(config.daemonCommand, directory, network = true, mergeOutput = true)) {
            is Outcome.Failure -> return false.also { fail(started.error.message) }
            is Outcome.Success -> Running(started.value, config.keptOutputChars, scope)
        }
        // Up once it answers on its socket.
        val up = withTimeoutOrNull(config.connectWaitSeconds * MILLIS_PER_SECOND) {
            while (daemon?.alive == true && status() == null) delay(config.pollMillis)
            daemon?.alive == true
        }
        if (up != true) {
            val detail = daemon?.output?.lastLine()
            stopDaemon()
            return false.also { fail(detail) }
        }
        return true
    }

    private suspend fun stopDaemon() {
        watcher?.cancel()
        watcher = null
        signIn?.stop()
        signIn = null
        daemon?.stop()
        daemon = null
    }

    /** Not running: not installed, signed out, or signed in as the last connection recorded. */
    private suspend fun idleState(): TailscaleState {
        val directory = folder() ?: return TailscaleState.NotInstalled
        val installed = (commands.run(config.checkCommand, directory, config.commandTimeoutSeconds) as? Outcome.Success)?.value?.exitCode == 0
        if (!installed) return TailscaleState.NotInstalled
        val marker = withContext(Dispatchers.IO) { linuxFile(config.markerFile)?.takeIf { it.isFile }?.readText()?.trim() }
        val (name, ip) = marker?.split(' ')?.let { it.getOrNull(0) to it.getOrNull(1) } ?: (null to null)
        return if (name.isNullOrBlank()) TailscaleState.SignedOut else TailscaleState.SignedIn(name, ip)
    }

    private suspend fun remember(name: String, ip: String) = withContext(Dispatchers.IO) {
        try {
            linuxFile(config.markerFile)?.apply { parentFile?.mkdirs() }?.writeText("$name $ip\n")
        } catch (exception: IOException) {
            // Only Settings' line while it's off; the next connection writes it again.
        }
    }

    private fun fail(detail: String?) {
        mutableState.value = TailscaleState.Failed(detail?.takeIf { it.isNotBlank() })
    }

    private fun lastLine(text: String): String? = text.lines().lastOrNull { it.isNotBlank() }?.trim()

    /** A process this class started, with its output read as it runs. */
    private inner class Running(val process: Process, keptChars: Int, scope: CoroutineScope) {
        val output = ProcessOutput(keptChars)
        private val reader = scope.launch(Dispatchers.IO) { output.read(process) }
        val alive: Boolean get() = process.isAlive

        suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
            starter.kill(process, output.processGroup)
            reader.cancel()
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
