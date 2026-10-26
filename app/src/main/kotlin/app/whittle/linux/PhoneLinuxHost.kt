package app.whittle.linux

import android.content.Context
import android.os.StatFs
import app.whittle.AppGraph
import app.whittle.apps.ToolchainFailure
import app.whittle.apps.ToolchainState
import app.whittle.linux.ui.AccessKind
import app.whittle.linux.ui.ComputerRow
import app.whittle.linux.ui.KeyIssue
import app.whittle.linux.ui.LinuxAction
import app.whittle.linux.ui.LinuxHost
import app.whittle.linux.ui.LinuxScreenState
import app.whittle.linux.ui.LinuxUsage
import app.whittle.linux.ui.RunningProcess
import app.whittle.linux.ui.SetupFacts
import app.whittle.linux.ui.SetupProgress
import app.whittle.linux.ui.SetupStep
import app.whittle.linux.ui.SetupStepStatus
import app.whittle.linux.ui.TailscaleKind
import app.whittle.linux.ui.TailscaleRow
import app.whittle.linux.ui.Toolchain
import app.whittle.linux.ui.ToolchainIssue
import app.whittle.settings.ui.LinuxKind
import app.whittle.tools.workspace.ProcessStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The phone's Linux as Settings › Linux workspace shows it, for the phone and the user's computers alike. Sizes read
 * the disk, so they're measured when the screen asks ([LinuxAction.Refresh]) and again as setup or the toolchain
 * change state, never on every progress tick.
 */
class PhoneLinuxHost(private val context: Context, private val graph: AppGraph) : LinuxHost {
    private val access = graph.computerAccess
    private val tailscale = access.tailscale
    private val toolchain = graph.appToolchain
    private val measured = MutableStateFlow(Measured())

    override val state: StateFlow<LinuxScreenState> = combine(
        combine(graph.linux.state, measured, running(), ::Triple),
        combine(toolchain.state, toolchain.building, ::Pair),
        combine(access.state, access.address, access.computers, ::Triple),
        tailscale.state,
    ) { (linux, sizes, running), (toolchainState, building), (computerAccess, wifi, computers), tailnet ->
        val image = graph.linux.image
        LinuxScreenState(
            kind = kindOf(linux),
            imageName = image?.name.orEmpty(),
            imageAvailable = image != null,
            usedBytes = sizes.usedBytes,
            usage = sizes.usage,
            facts = image?.let { SetupFacts(downloadBytes = it.sizeBytes, setupBytes = it.setupSizeBytes, freeBytes = sizes.freeBytes) },
            progress = progress(linux, image?.name.orEmpty()),
            running = running,
            toolchain = Toolchain(
                loaded = sizes.toolchainRead,
                installing = toolchainState is ToolchainState.Installing,
                building = building,
                ready = sizes.toolchainReady,
                sizeBytes = sizes.toolchainBytes,
                removeFailed = sizes.removeFailed?.let { if (it == ToolchainFailure.InUse) ToolchainIssue.InUse else ToolchainIssue.Other },
            ),
            access = accessOf(computerAccess, wifi, tailnet, computers),
            tailscale = rowOf(tailnet),
        )
    }.stateIn(graph.appScope, SharingStarted.Eagerly, LinuxScreenState())

    init {
        // A new state of setup or the toolchain changes what's on disk; their progress ticks don't.
        graph.linux.state.map { kindOf(it) }.distinctUntilChanged().drop(1).onEach { measureLinux() }.launchIn(graph.appScope)
        toolchain.state.map { it::class }.distinctUntilChanged().drop(1).onEach {
            measured.update { it.copy(removeFailed = null) }
            measureToolchain()
        }.launchIn(graph.appScope)
    }

    // Everything runs in the app's scope, so leaving the screen or the link dropping never stops a change half way.
    override fun act(action: LinuxAction) {
        when (action) {
            LinuxAction.Refresh -> {
                launch {
                    access.load()
                    tailscale.check()
                }
                launch { measureLinux() }
                launch { measureToolchain() }
            }
            LinuxAction.SetUp -> LinuxSetupService.setUp(context)
            LinuxAction.Repair -> LinuxSetupService.repair(context)
            LinuxAction.Reset -> {
                // Nothing may sign in to a Linux that's being replaced or removed.
                access.turnOff()
                LinuxSetupService.reset(context)
            }
            LinuxAction.Remove -> {
                access.turnOff()
                launch { graph.linux.remove() }
            }
            LinuxAction.ClearCaches -> launch {
                graph.linux.clearCaches()
                measureLinux()
            }
            LinuxAction.StopAll -> launch { graph.processes.stopAll() }
            LinuxAction.RemoveToolchain -> launch {
                val failed = toolchain.remove()
                measured.update { it.copy(removeFailed = failed) }
                measureToolchain()
            }
            is LinuxAction.SetAccess -> if (action.on) access.turnOn() else access.turnOff()
            LinuxAction.TailscaleInstall -> tailscale.install()
            LinuxAction.TailscaleSignIn -> access.signInToTailscale()
            is LinuxAction.TailscaleSignOut -> tailscale.signOut(action.remove)
            LinuxAction.TailscaleRetry -> launch { tailscale.check() }
            is LinuxAction.RemoveComputer -> launch {
                access.computers.value.firstOrNull { it.fingerprint == action.fingerprint }?.let { access.remove(it) }
            }
        }
    }

    override suspend fun addComputer(key: String): KeyIssue? = when (access.add(key)) {
        null -> null
        KeyProblem.NOT_A_KEY -> KeyIssue.NotAKey
        KeyProblem.TYPE_NOT_ALLOWED -> KeyIssue.TypeNotAllowed
        KeyProblem.ALREADY_ADDED -> KeyIssue.AlreadyAdded
        KeyProblem.TOO_MANY -> KeyIssue.TooMany
    }

    private fun launch(block: suspend () -> Unit) = graph.appScope.launch { block() }

    /** What Linux takes, where it went, and what's free; null while it's being read again. */
    private suspend fun measureLinux() {
        measured.update { it.copy(usedBytes = null, usage = null) }
        val free = StatFs(context.filesDir.path).availableBytes
        val ready = graph.linux.state.value is LinuxState.Ready
        val used = graph.linux.usedBytes()
        val usage = if (ready) graph.linux.usage().let { LinuxUsage(base = it.base, added = it.added, caches = it.caches) } else null
        measured.update { it.copy(usedBytes = used, usage = usage, freeBytes = free) }
    }

    /** Whether the toolchain finished setting up, and what it takes; unread while it installs. */
    private suspend fun measureToolchain() {
        if (toolchain.state.value is ToolchainState.Installing) {
            measured.update { it.copy(toolchainRead = false) }
            return
        }
        val ready = toolchain.isReady()
        val size = toolchain.size()
        measured.update { it.copy(toolchainRead = true, toolchainReady = ready, toolchainBytes = size) }
    }

    /** What's running, as it changes: not on every line a process prints. */
    private fun running() = graph.processes.processes.map { all ->
        all.filter { it.live }.map { process ->
            RunningProcess(
                name = process.name,
                port = process.port?.takeIf { process.listening },
                starting = process.status == ProcessStatus.Starting || (process.port != null && !process.listening),
            )
        }
    }.distinctUntilChanged()

    private fun progress(state: LinuxState, imageName: String): SetupProgress? {
        val (from, current) = when (state) {
            is LinuxState.SettingUp -> state.from to state.step
            is LinuxState.Failed -> state.from to state.step
            else -> return null
        }
        val steps = LinuxSetupStep.entries.filter { it.ordinal in from.ordinal..current.ordinal }.map { step ->
            val now = step == current
            SetupStep(
                id = step.name,
                icon = STEP_ICONS.getValue(step),
                label = LinuxSteps.label(context, step, done = !now, imageName),
                detail = when {
                    !now -> null
                    state is LinuxState.Failed -> state.reason
                    state is LinuxState.SettingUp -> LinuxSteps.detail(context, state)
                    else -> null
                },
                status = when {
                    !now -> SetupStepStatus.Done
                    state is LinuxState.Failed -> SetupStepStatus.Failed
                    else -> SetupStepStatus.Running
                },
            )
        }
        val settingUp = state as? LinuxState.SettingUp
        return SetupProgress(
            steps = steps,
            running = settingUp != null,
            percent = settingUp?.takeIf { it.totalBytes > 0 }?.let(LinuxSteps::percent),
        )
    }

    private fun accessOf(state: ComputerAccessState, wifi: String?, tailnet: TailscaleState, computers: List<ComputerKey>): app.whittle.linux.ui.ComputerAccess {
        val on = state as? ComputerAccessState.On
        val config = access.config
        // The tailnet address while it's on, and the last one Tailscale gave while it's off.
        val tailnetAddress = on?.tailscale ?: (tailnet as? TailscaleState.SignedIn)?.ip ?: (tailnet as? TailscaleState.Connected)?.ip
        return app.whittle.linux.ui.ComputerAccess(
            kind = when (state) {
                ComputerAccessState.Off -> AccessKind.Off
                ComputerAccessState.Installing -> AccessKind.Installing
                ComputerAccessState.Starting -> AccessKind.Starting
                is ComputerAccessState.On -> AccessKind.On
                is ComputerAccessState.Failed -> AccessKind.Failed
            },
            wifi = on?.wifi,
            tailscale = on?.tailscale,
            detail = (state as? ComputerAccessState.Failed)?.detail,
            reachable = wifi != null || tailscale.signedIn,
            computers = computers.map { ComputerRow(comment = it.comment, fingerprint = it.fingerprint, type = it.type.removePrefix(KEY_TYPE_PREFIX)) },
            wifiAddress = wifi,
            signInWifi = wifi?.let(config::signIn),
            signInTailscale = tailnetAddress?.let(config::signIn),
            printKeyCommand = config.printKeyCommand,
            makeKeyCommand = config.makeKeyCommand,
            maxComputers = config.maxComputers,
        )
    }

    private fun rowOf(state: TailscaleState): TailscaleRow = when (state) {
        TailscaleState.Checking -> TailscaleRow(TailscaleKind.Checking)
        TailscaleState.NotInstalled -> TailscaleRow(TailscaleKind.NotInstalled)
        TailscaleState.Installing -> TailscaleRow(TailscaleKind.Installing)
        TailscaleState.SignedOut -> TailscaleRow(TailscaleKind.SignedOut)
        is TailscaleState.SignedIn -> TailscaleRow(TailscaleKind.SignedIn, name = state.name)
        TailscaleState.Connecting -> TailscaleRow(TailscaleKind.Connecting)
        TailscaleState.SigningOut -> TailscaleRow(TailscaleKind.SigningOut)
        TailscaleState.Removing -> TailscaleRow(TailscaleKind.Removing)
        is TailscaleState.WaitingForSignIn -> TailscaleRow(TailscaleKind.WaitingForSignIn, url = state.url)
        is TailscaleState.Connected -> TailscaleRow(TailscaleKind.Connected, name = state.name)
        is TailscaleState.Failed -> TailscaleRow(TailscaleKind.Failed, detail = state.detail)
    }

    /** What's been read from the disk; each part null (or unread) until it has been. */
    private data class Measured(
        val usedBytes: Long? = null,
        val usage: LinuxUsage? = null,
        val freeBytes: Long = 0,
        val toolchainRead: Boolean = false,
        val toolchainReady: Boolean = false,
        val toolchainBytes: Long? = null,
        val removeFailed: ToolchainFailure? = null,
    )

    private companion object {
        /** OpenSSH names key types "ssh-ed25519"; people say "ed25519". */
        const val KEY_TYPE_PREFIX = "ssh-"
        val STEP_ICONS = mapOf(
            LinuxSetupStep.Download to "download",
            LinuxSetupStep.Unpack to "folder_zip",
            LinuxSetupStep.Configure to "shield",
            LinuxSetupStep.BasePackages to "package_2",
        )
    }
}

/** The kind of state Settings tells apart. */
fun kindOf(state: LinuxState): LinuxKind = when (state) {
    LinuxState.NotSetUp -> LinuxKind.NotSetUp
    is LinuxState.SettingUp -> LinuxKind.SettingUp
    is LinuxState.Ready -> LinuxKind.Ready
    is LinuxState.Failed -> LinuxKind.Failed
}
