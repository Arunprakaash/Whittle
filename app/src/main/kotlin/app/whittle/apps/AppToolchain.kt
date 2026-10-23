package app.whittle.apps

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.workspace.CommandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * The toolchain that builds apps in Linux (docs/native-apps.md, "The toolchain"). It is ready when the marker the
 * setup script writes last holds the hash of the current pins, so changed pins, or a changed script, set it up again.
 */
class AppToolchain(private val shell: LinuxShell, config: NativeAppsConfig) {
    private val pins = config.toolchain
    private val hash = pinsHash(config)
    private val lock = Mutex()
    private val _state = MutableStateFlow<ToolchainState>(ToolchainState.NotInstalled)
    val state: StateFlow<ToolchainState> = _state.asStateFlow()

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
            _state.value = ToolchainState.Failed(failure)
            return Outcome.Failure(HarnessError.Storage(failure))
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

    /** Deletes the toolchain; the next build asks to set it up again. */
    suspend fun remove(): Outcome<Unit> = lock.withLock {
        when (val ran = shell.run("rm -rf $DIRECTORY", network = false, timeoutSeconds = REMOVE_TIMEOUT_SECONDS)) {
            is Outcome.Failure -> ran
            is Outcome.Success -> {
                val exit = ran.value.exitCode
                if (exit == 0) {
                    _state.value = ToolchainState.NotInstalled
                    Outcome.Success(Unit)
                } else {
                    Outcome.Failure(HarnessError.Storage(ran.value.stderr.trim().takeLast(MAX_REASON_CHARS).ifBlank { "Couldn't remove (exit $exit)" }))
                }
            }
        }
    }

    private fun failureOf(result: CommandResult): String? = when {
        result.timedOut -> "Setup took too long"
        result.exitCode != 0 -> (result.stderr.ifBlank { result.stdout }).trim().takeLast(MAX_REASON_CHARS).ifBlank { "Setup failed (exit ${result.exitCode})" }
        else -> null
    }

    companion object {
        const val DIRECTORY = "/opt/whittle-apps"
        const val MARKER = "$DIRECTORY/ready"
        private const val PINS_HASH = "{{pinsHash}}"
        private const val CHECK_TIMEOUT_SECONDS = 30
        private const val REMOVE_TIMEOUT_SECONDS = 300
        private const val MAX_REASON_CHARS = 400
        private const val CANCELLED = "Setup was interrupted"
        private const val NOT_CONFIRMED = "Setup finished but the toolchain isn't ready"

        /** The lowercase hex SHA-256 of the pins as JSON; covers the setup script too. */
        fun pinsHash(config: NativeAppsConfig): String {
            val json = Json.encodeToString(ToolchainPins.serializer(), config.toolchain)
            return MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Starts the setup with [start] and waits for how it ends: true when the toolchain is ready, false when setup failed,
 * was interrupted, or couldn't start. If setup doesn't begin within [startTimeout], the answer is whether the
 * toolchain is ready already. Never waits forever on a service that didn't start.
 */
suspend fun awaitSetup(toolchain: AppToolchain, start: () -> Unit, startTimeout: Duration): Boolean {
    try {
        start()
    } catch (exception: RuntimeException) {
        return false
    }
    // A Failed left from an earlier try isn't this run's outcome: wait for the run to start over first.
    val before = toolchain.state.value
    val begun = withTimeoutOrNull(startTimeout) {
        toolchain.state.first { if (before is ToolchainState.Failed) it is ToolchainState.Installing || it is ToolchainState.Ready else it !is ToolchainState.NotInstalled }
    } ?: return toolchain.isReady()
    if (begun is ToolchainState.Ready) return true
    return toolchain.state.first { it is ToolchainState.Ready || it is ToolchainState.Failed } is ToolchainState.Ready
}
