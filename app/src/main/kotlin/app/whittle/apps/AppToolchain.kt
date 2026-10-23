package app.whittle.apps

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.workspace.CommandResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** The capability the agent asks for before it can build an app; granting it runs the toolchain setup. */
val APP_BUILDING = CapabilityId("app_building")

/** Runs one command in Linux; the app implements it with its command runner. */
fun interface LinuxShell {
    suspend fun run(command: String, network: Boolean, timeoutSeconds: Int): Outcome<CommandResult>
}

sealed interface ToolchainState {
    data object NotInstalled : ToolchainState

    data object Installing : ToolchainState

    data object Ready : ToolchainState

    data class Failed(val reason: String) : ToolchainState
}

/** What a build needs from the toolchain. */
interface BuildToolchain {
    /**
     * Runs [build] while holding the toolchain, so Remove refuses until it ends. Null, without running it, when the
     * toolchain isn't ready or is being removed.
     */
    suspend fun <T> whileReady(build: suspend () -> T): T?
}

/**
 * The toolchain that builds apps in Linux (docs/native-apps.md, "The toolchain"). It is ready when the marker the
 * setup script writes last holds the hash of the current pins, so changed pins, or a changed script, set it up again.
 */
class AppToolchain(private val shell: LinuxShell, config: NativeAppsConfig) : BuildToolchain {
    private val pins = config.toolchain
    private val hash = pinsHash(config)
    private val maxReasonChars = config.maxReasonChars
    private val lock = Mutex()
    private val _state = MutableStateFlow<ToolchainState>(ToolchainState.NotInstalled)
    val state: StateFlow<ToolchainState> = _state.asStateFlow()

    /** Guards [builds] and [removing], which let builds run side by side but never with Remove. */
    private val holds = Mutex()
    private var builds = 0
    private var removing = false
    private val _building = MutableStateFlow(false)

    /** Whether a build is using the toolchain, so it can't be removed. */
    val building: StateFlow<Boolean> = _building.asStateFlow()

    /** Whether the marker holds the current pins hash; the state follows the answer, except while setup runs. */
    suspend fun isReady(): Boolean {
        val ran = shell.run("cat $MARKER", network = false, timeoutSeconds = CHECK_TIMEOUT_SECONDS)
        val result = (ran as? Outcome.Success)?.value
        val ready = result != null && result.exitCode == 0 && result.stdout.trim() == hash
        _state.update { current ->
            when {
                current is ToolchainState.Installing -> current
                ready -> ToolchainState.Ready
                current is ToolchainState.Ready -> ToolchainState.NotInstalled
                else -> current
            }
        }
        return ready
    }

    /** Sets the toolchain up. A call while setup is running waits for that run instead of starting another. */
    suspend fun install(): Outcome<Unit> {
        if (!lock.tryLock()) {
            lock.withLock { }
            return when (val current = _state.value) {
                is ToolchainState.Failed -> Outcome.Failure(HarnessError.Storage(current.reason))
                else -> Outcome.Success(Unit)
            }
        }
        try {
            _state.value = ToolchainState.Installing
            val script = pins.setupScript.replace(PINS_HASH, hash)
            val failure = when (val ran = shell.run(script, network = true, timeoutSeconds = pins.setupTimeoutSeconds)) {
                is Outcome.Failure -> ran.error.message
                is Outcome.Success -> failureOf(ran.value) ?: if (isReady()) null else NOT_CONFIRMED
            }
            if (failure == null) {
                _state.value = ToolchainState.Ready
                return Outcome.Success(Unit)
            }
            val reason = capped(failure)
            _state.value = ToolchainState.Failed(reason)
            return Outcome.Failure(HarnessError.Storage(reason))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            _state.value = ToolchainState.Failed(CANCELLED)
            throw cancellation
        } finally {
            lock.unlock()
        }
    }

    /** The toolchain's size on disk as `du -sh` prints it, or null when it isn't there. */
    suspend fun size(): String? {
        val result = (shell.run("du -sh $DIRECTORY", network = false, timeoutSeconds = CHECK_TIMEOUT_SECONDS) as? Outcome.Success)?.value
        return result?.takeIf { it.exitCode == 0 }?.stdout?.trim()?.substringBefore('\t')?.substringBefore(' ')?.ifBlank { null }
    }

    override suspend fun <T> whileReady(build: suspend () -> T): T? {
        val held = holds.withLock {
            if (!removing) {
                builds++
                _building.value = true
            }
            !removing
        }
        if (!held) return null
        try {
            return if (isReady()) build() else null
        } finally {
            withContext(NonCancellable) {
                holds.withLock {
                    builds--
                    _building.value = builds > 0
                }
            }
        }
    }

    /** Deletes the toolchain; the next build asks to set it up again. Refused while a build is using it. */
    suspend fun remove(): Outcome<Unit> = lock.withLock {
        val free = holds.withLock {
            if (builds == 0) removing = true
            builds == 0
        }
        if (!free) return@withLock Outcome.Failure(HarnessError.Storage(IN_USE))
        try {
            when (val ran = shell.run("rm -rf $DIRECTORY", network = false, timeoutSeconds = REMOVE_TIMEOUT_SECONDS)) {
                is Outcome.Failure -> ran
                is Outcome.Success -> {
                    val exit = ran.value.exitCode
                    if (exit == 0) {
                        _state.value = ToolchainState.NotInstalled
                        Outcome.Success(Unit)
                    } else {
                        Outcome.Failure(HarnessError.Storage(capped(ran.value.stderr.trim()).ifBlank { "Couldn't remove (exit $exit)" }))
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { holds.withLock { removing = false } }
        }
    }

    /** The end of [reason], which holds the error, at most [NativeAppsConfig.maxReasonChars] long. */
    fun capped(reason: String): String = reason.takeLast(maxReasonChars)

    private fun failureOf(result: CommandResult): String? = when {
        result.timedOut -> "Setup took too long"
        result.exitCode != 0 -> (result.stderr.ifBlank { result.stdout }).trim().ifBlank { "Setup failed (exit ${result.exitCode})" }
        else -> null
    }

    companion object {
        const val DIRECTORY = "/opt/whittle-apps"
        const val MARKER = "$DIRECTORY/ready"
        private const val PINS_HASH = "{{pinsHash}}"
        private const val CHECK_TIMEOUT_SECONDS = 30
        private const val REMOVE_TIMEOUT_SECONDS = 300
        private const val IN_USE = "An app is building"
        private const val CANCELLED = "Setup was interrupted"
        private const val NOT_CONFIRMED = "Setup finished but the toolchain isn't ready"

        /** The lowercase hex SHA-256 of the pins as JSON; covers the setup script too. */
        fun pinsHash(config: NativeAppsConfig): String {
            val json = Json.encodeToString(ToolchainPins.serializer(), config.toolchain)
            return MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** How the user's Allow on the app-building card ended. */
sealed interface SetupOutcome {
    data object Ready : SetupOutcome

    /** Linux isn't set up, so app building's setup didn't start. */
    data object NeedsLinux : SetupOutcome

    data class Failed(val reason: String) : SetupOutcome
}

/**
 * Allow on the app-building card: nothing starts until Linux is ready; a toolchain that is there already is ready at
 * once; otherwise [start] runs the setup and the answer is how it ended (see [awaitSetup]).
 */
suspend fun prepareAppBuilding(linuxReady: Boolean, toolchain: AppToolchain, start: () -> Unit, startTimeout: Duration): SetupOutcome = when {
    !linuxReady -> SetupOutcome.NeedsLinux
    toolchain.isReady() -> SetupOutcome.Ready
    else -> awaitSetup(toolchain, start, startTimeout)
}

/**
 * Starts the setup with [start] and waits for how it ends: ready, or failed with the reason, also when it was
 * interrupted or couldn't start. If setup doesn't begin within [startTimeout], the answer is whether the toolchain
 * is ready already. Never waits forever on a service that didn't start.
 */
suspend fun awaitSetup(toolchain: AppToolchain, start: () -> Unit, startTimeout: Duration): SetupOutcome {
    try {
        start()
    } catch (exception: RuntimeException) {
        return SetupOutcome.Failed(toolchain.capped(exception.message ?: NOT_STARTED))
    }
    // A Failed left from an earlier try isn't this run's outcome: wait for the run to start over first.
    val before = toolchain.state.value
    val begun = withTimeoutOrNull(startTimeout) {
        toolchain.state.first { if (before is ToolchainState.Failed) it is ToolchainState.Installing || it is ToolchainState.Ready else it !is ToolchainState.NotInstalled }
    } ?: return if (toolchain.isReady()) SetupOutcome.Ready else SetupOutcome.Failed(NOT_STARTED)
    if (begun is ToolchainState.Ready) return SetupOutcome.Ready
    return when (val ended = toolchain.state.first { it is ToolchainState.Ready || it is ToolchainState.Failed }) {
        is ToolchainState.Failed -> SetupOutcome.Failed(ended.reason)
        else -> SetupOutcome.Ready
    }
}

/** The line of a setup failure the card shows: its last one, where the error usually is. */
fun shortReason(reason: String): String = reason.lines().lastOrNull { it.isNotBlank() }?.trim() ?: reason.trim()

private const val NOT_STARTED = "Setup didn't start"
