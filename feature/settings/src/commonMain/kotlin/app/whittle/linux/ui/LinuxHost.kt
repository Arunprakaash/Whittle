package app.whittle.linux.ui

import app.whittle.settings.ui.LinuxKind
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Settings › Linux workspace, from wherever it's shown: the phone's own Linux directly on the phone, the same over the link
 * on a computer. Sizes come measured; words that depend on the phone (setup's steps) come in the phone's language.
 */
interface LinuxHost {
    val state: StateFlow<LinuxScreenState>

    fun act(action: LinuxAction)

    /** Lets a computer sign in with the public key [key]; null when it was added, otherwise why not. */
    suspend fun addComputer(key: String): KeyIssue?
}

@Serializable
data class LinuxScreenState(
    val kind: LinuxKind = LinuxKind.NotSetUp,
    val imageName: String = "",
    /** There's a Linux image for this phone's processor. */
    val imageAvailable: Boolean = true,
    /** What Linux takes on the phone; null until measured. */
    val usedBytes: Long? = null,
    /** Where that space went; null until measured. */
    val usage: LinuxUsage? = null,
    /** Before setup: what it downloads, what it takes, and what's free. */
    val facts: SetupFacts? = null,
    /** Setting up, or failed: this run's steps so far. */
    val progress: SetupProgress? = null,
    /** The names of what the agent has running in Linux. */
    val running: List<String> = emptyList(),
    val toolchain: Toolchain = Toolchain(),
    val access: ComputerAccess = ComputerAccess(),
    val tailscale: TailscaleRow = TailscaleRow(),
)

@Serializable
data class LinuxUsage(val base: Long, val added: Long, val caches: Long)

@Serializable
data class SetupFacts(val downloadBytes: Long, val setupBytes: Long, val freeBytes: Long)

/** Setup's steps as a chat shows steps, and how far the current one is, when it counts. */
@Serializable
data class SetupProgress(val steps: List<SetupStep>, val running: Boolean, val percent: Int? = null)

@Serializable
data class SetupStep(val id: String, val icon: String, val label: String, val detail: String?, val status: SetupStepStatus)

@Serializable
enum class SetupStepStatus { Done, Running, Failed }

/** The app-building toolchain; [loaded] is false until it's been read. */
@Serializable
data class Toolchain(
    val loaded: Boolean = false,
    val installing: Boolean = false,
    val building: Boolean = false,
    val ready: Boolean = false,
    val sizeBytes: Long? = null,
    val removeFailed: ToolchainIssue? = null,
) {
    /** Remove is offered once it's there, and never while it installs or builds. */
    val removable: Boolean get() = (ready || sizeBytes != null) && !installing && !building
}

@Serializable
enum class ToolchainIssue { InUse, Other }

/** Using Linux from a computer: the switch, how to sign in while it's on, and the computers that may. */
@Serializable
data class ComputerAccess(
    val kind: AccessKind = AccessKind.Off,
    val wifi: String? = null,
    val tailscale: String? = null,
    /** Why it stopped or didn't turn on, when the server said. */
    val detail: String? = null,
    /** There's a way in right now: Wi-Fi, or Tailscale signed in. */
    val reachable: Boolean = false,
    val computers: List<ComputerRow> = emptyList(),
    /** What a computer types to sign in, on the same Wi-Fi and over Tailscale. */
    val signInWifi: String? = null,
    val signInTailscale: String? = null,
    /** Shown in Add a computer: prints the computer's key, and makes one when there's none. */
    val printKeyCommand: String = "",
    val makeKeyCommand: String = "",
    val maxComputers: Int = 0,
)

@Serializable
enum class AccessKind { Off, Installing, Starting, On, Failed }

@Serializable
data class ComputerRow(val comment: String, val fingerprint: String)

@Serializable
data class TailscaleRow(
    val kind: TailscaleKind = TailscaleKind.Checking,
    /** The phone's name on the tailnet, while signed in. */
    val name: String? = null,
    /** The sign-in link, while it waits for the user. */
    val url: String? = null,
    val detail: String? = null,
)

@Serializable
enum class TailscaleKind { Checking, NotInstalled, Installing, SignedOut, SignedIn, Connecting, SigningOut, Removing, WaitingForSignIn, Connected, Failed }

/** Why a key wasn't added; [NoAnswer] when a computer's phone didn't answer, so the key may not have been. */
@Serializable
enum class KeyIssue { NotAKey, TypeNotAllowed, AlreadyAdded, TooMany, NoAnswer }

@Serializable
sealed interface LinuxAction {
    /** Reads again what the screen shows: sizes, the toolchain, the computers and Tailscale; as the screen opens. */
    @Serializable
    @SerialName("refresh")
    data object Refresh : LinuxAction

    @Serializable
    @SerialName("set_up")
    data object SetUp : LinuxAction

    @Serializable
    @SerialName("repair")
    data object Repair : LinuxAction

    @Serializable
    @SerialName("reset")
    data object Reset : LinuxAction

    @Serializable
    @SerialName("remove")
    data object Remove : LinuxAction

    @Serializable
    @SerialName("clear_caches")
    data object ClearCaches : LinuxAction

    @Serializable
    @SerialName("stop_all")
    data object StopAll : LinuxAction

    @Serializable
    @SerialName("remove_toolchain")
    data object RemoveToolchain : LinuxAction

    @Serializable
    @SerialName("access")
    data class SetAccess(val on: Boolean) : LinuxAction

    @Serializable
    @SerialName("tailscale_install")
    data object TailscaleInstall : LinuxAction

    @Serializable
    @SerialName("tailscale_sign_in")
    data object TailscaleSignIn : LinuxAction

    /** Signs the phone out of the tailnet; with [remove], uninstalls Tailscale too. */
    @Serializable
    @SerialName("tailscale_sign_out")
    data class TailscaleSignOut(val remove: Boolean) : LinuxAction

    @Serializable
    @SerialName("tailscale_retry")
    data object TailscaleRetry : LinuxAction

    @Serializable
    @SerialName("remove_computer")
    data class RemoveComputer(val fingerprint: String) : LinuxAction
}

/** What the Linux screen does through the device: open Tailscale's sign-in link by itself where it may. */
class LinuxPlatform(
    /** The device opens a new sign-in link by itself; elsewhere the row's Open link does. */
    val opensLinksItself: Boolean,
)
