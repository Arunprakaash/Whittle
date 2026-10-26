package app.whittle.apps

import app.whittle.agent.AgentMessages
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

/**
 * Why setup, or a removal, stopped short. The words for each are config: agent-messages.json for the agent
 * ([setupReasonForModel]) and strings.xml for the card and Settings. Only [Output] comes with what the command printed.
 */
enum class ToolchainFailure { TimedOut, Interrupted, NotConfirmed, NotStarted, InUse, Output }

sealed interface ToolchainState {
    data object NotInstalled : ToolchainState

    data object Installing : ToolchainState

    data object Ready : ToolchainState

    /** [detail] is raw text, a command's output or an exception's message, capped; it is data, never the words shown. */
    data class Failed(val kind: ToolchainFailure, val detail: String? = null) : ToolchainState
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
                is ToolchainState.Failed -> Outcome.Failure(HarnessError.Storage(current.detail ?: current.kind.name))
                else -> Outcome.Success(Unit)
            }
        }
        try {
            _state.value = ToolchainState.Installing
            val script = pins.setupScript.replace(PINS_HASH, hash)
            val failure = when (val ran = shell.run(script, network = true, timeoutSeconds = pins.setupTimeoutSeconds)) {
                is Outcome.Failure -> failed(ToolchainFailure.Output, ran.error.message)
                is Outcome.Success -> failureOf(ran.value) ?: if (isReady()) null else failed(ToolchainFailure.NotConfirmed)
            }
            if (failure == null) {
                _state.value = ToolchainState.Ready
                return Outcome.Success(Unit)
            }
            _state.value = failure
            return Outcome.Failure(HarnessError.Storage(failure.detail ?: failure.kind.name))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            _state.value = failed(ToolchainFailure.Interrupted)
            throw cancellation
        } finally {
            lock.unlock()
        }
    }

    /** The toolchain's size on disk in bytes, or null when it isn't there. */
    suspend fun size(): Long? {
        val result = (shell.run("du -sk $DIRECTORY", network = false, timeoutSeconds = CHECK_TIMEOUT_SECONDS) as? Outcome.Success)?.value
        return result?.takeIf { it.exitCode == 0 }?.stdout?.let(::duBytes)
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

    /**
     * Deletes the toolchain, also one whose setup never finished; the next build asks to set it up again. Null once it's
     * gone; [ToolchainFailure.InUse] while a build is using it, or [ToolchainFailure.Output] when deleting failed.
     */
    suspend fun remove(): ToolchainFailure? = lock.withLock {
        val free = holds.withLock {
            if (builds == 0) removing = true
            builds == 0
        }
        if (!free) return@withLock ToolchainFailure.InUse
        try {
            val ran = (shell.run("rm -rf $DIRECTORY", network = false, timeoutSeconds = REMOVE_TIMEOUT_SECONDS) as? Outcome.Success)?.value
            if (ran?.exitCode == 0) {
                _state.value = ToolchainState.NotInstalled
                null
            } else {
                ToolchainFailure.Output
            }
        } finally {
            withContext(NonCancellable) { holds.withLock { removing = false } }
        }
    }

    /** A failure of [kind], with the end of [detail] (where the error is), at most [NativeAppsConfig.maxReasonChars] long. */
    fun failed(kind: ToolchainFailure, detail: String? = null): ToolchainState.Failed =
        ToolchainState.Failed(kind, detail?.trim()?.takeLast(maxReasonChars)?.ifBlank { null })

    private fun failureOf(result: CommandResult): ToolchainState.Failed? = when {
        result.timedOut -> failed(ToolchainFailure.TimedOut)
        result.exitCode != 0 -> failed(ToolchainFailure.Output, result.stderr.ifBlank { result.stdout })
        else -> null
    }

    companion object {
        const val DIRECTORY = "/opt/whittle-apps"
        const val MARKER = "$DIRECTORY/ready"
        private const val PINS_HASH = "{{pinsHash}}"
        private const val CHECK_TIMEOUT_SECONDS = 30
        private const val REMOVE_TIMEOUT_SECONDS = 300

        /** The lowercase hex SHA-256 of the pins as JSON; covers the setup script too. */
        fun pinsHash(config: NativeAppsConfig): String {
            val json = Json.encodeToString(ToolchainPins.serializer(), config.toolchain)
            return MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** Bytes from `du -sk` output ("153600\t/opt/whittle-apps"); null when it names none. */
fun duBytes(output: String): Long? = output.trim().substringBefore('\t').substringBefore(' ').toLongOrNull()?.times(KIBIBYTE)

private const val KIBIBYTE = 1024L

/** How the user's Allow on the app-building card ended. */
sealed interface SetupOutcome {
    data object Ready : SetupOutcome

    /** Linux isn't set up, so app building's setup didn't start. */
    data object NeedsLinux : SetupOutcome

    data class Failed(val kind: ToolchainFailure, val detail: String? = null) : SetupOutcome
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
        return toolchain.failed(ToolchainFailure.NotStarted, exception.message).let { SetupOutcome.Failed(it.kind, it.detail) }
    }
    // A Failed left from an earlier try isn't this run's outcome: wait for the run to start over first.
    val before = toolchain.state.value
    val begun = withTimeoutOrNull(startTimeout) {
        toolchain.state.first { if (before is ToolchainState.Failed) it is ToolchainState.Installing || it is ToolchainState.Ready else it !is ToolchainState.NotInstalled }
    } ?: return if (toolchain.isReady()) SetupOutcome.Ready else SetupOutcome.Failed(ToolchainFailure.NotStarted)
    if (begun is ToolchainState.Ready) return SetupOutcome.Ready
    return when (val ended = toolchain.state.first { it is ToolchainState.Ready || it is ToolchainState.Failed }) {
        is ToolchainState.Failed -> SetupOutcome.Failed(ended.kind, ended.detail)
        else -> SetupOutcome.Ready
    }
}

/** The line of a failure's detail the card shows: its last one, where the error usually is. */
fun shortReason(reason: String): String = reason.lines().lastOrNull { it.isNotBlank() }?.trim() ?: reason.trim()

/** What the agent is told about a failed setup: the config words for [kind], then the raw [detail] on its own line. */
fun setupReasonForModel(messages: AgentMessages, kind: ToolchainFailure, detail: String?): String {
    val words = when (kind) {
        ToolchainFailure.TimedOut -> messages.appSetupTimedOut
        ToolchainFailure.Interrupted -> messages.appSetupInterrupted
        ToolchainFailure.NotConfirmed -> messages.appSetupNotConfirmed
        ToolchainFailure.NotStarted -> messages.appSetupNotStarted
        ToolchainFailure.InUse -> messages.appSetupInUse
        ToolchainFailure.Output -> messages.appSetupOutput
    }
    return if (detail.isNullOrBlank()) words else words + "\n" + detail
}
