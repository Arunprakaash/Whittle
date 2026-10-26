package app.whittle.settings.ui

import androidx.compose.runtime.Composable
import app.whittle.design.theme.ThemeMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where Settings come from and changes go: the phone's own, directly there and over the link on a computer. */
interface SettingsHost {
    val state: StateFlow<SettingsState>

    fun act(action: SettingsAction)
}

/** Everything the Settings page shows. */
@Serializable
data class SettingsState(
    /** False until the saved settings are read; the switches wait for them. */
    val loaded: Boolean = false,
    /** The model's name; null before one is connected. */
    val model: String? = null,
    val linux: LinuxStatus = LinuxStatus(),
    val memories: Int = 0,
    val automationsOn: Int = 0,
    val themeMode: ThemeMode = ThemeMode.System,
    val wallpaperColors: Boolean = false,
    /** The phone can take colours from its wallpaper (Android 12+). */
    val wallpaperAvailable: Boolean = false,
    val haptics: Boolean = false,
    val keepRunningInBackground: Boolean = false,
    /** This build can share usage data at all. */
    val analyticsAvailable: Boolean = false,
    val shareUsageData: Boolean = false,
    val screenRecordings: Boolean = false,
    val version: String = "",
    val update: UpdateStatus = UpdateStatus(),
    val checkForUpdates: Boolean = false,
    /** When GitHub last answered the update check; null before it ever has. */
    val updatesAnsweredAt: Long? = null,
    val updatesLastFailed: Boolean = false,
)

@Serializable
data class LinuxStatus(val kind: LinuxKind = LinuxKind.NotSetUp, val imageName: String = "")

@Serializable
enum class LinuxKind { NotSetUp, SettingUp, Ready, Failed }

/** A newer Whittle, and how updating to it is going. */
@Serializable
data class UpdateStatus(
    /** The newer version out; null when this one is the latest. */
    val latest: String? = null,
    /** Its release page. */
    val pageUrl: String? = null,
    val progress: UpdateProgress = UpdateProgress.Idle,
)

@Serializable
sealed interface UpdateProgress {
    @Serializable
    @SerialName("idle")
    data object Idle : UpdateProgress

    /** Android must first let Whittle install apps. */
    @Serializable
    @SerialName("needs_permission")
    data object NeedsPermission : UpdateProgress

    @Serializable
    @SerialName("downloading")
    data class Downloading(val version: String, val percent: Int) : UpdateProgress

    @Serializable
    @SerialName("installing")
    data class Installing(val version: String) : UpdateProgress

    @Serializable
    @SerialName("failed")
    data class Failed(val reason: UpdateFailureReason) : UpdateProgress
}

@Serializable
enum class UpdateFailureReason { Network, Mismatch, NotWhittle, Install, NoApk }

/** What the Settings page changes. */
@Serializable
sealed interface SettingsAction {
    @Serializable
    @SerialName("theme")
    data class SetTheme(val mode: ThemeMode) : SettingsAction

    @Serializable
    @SerialName("wallpaper")
    data class SetWallpaperColors(val on: Boolean) : SettingsAction

    @Serializable
    @SerialName("haptics")
    data class SetHaptics(val on: Boolean) : SettingsAction

    @Serializable
    @SerialName("background")
    data class SetKeepRunning(val on: Boolean) : SettingsAction

    @Serializable
    @SerialName("usage")
    data class SetShareUsage(val on: Boolean) : SettingsAction

    @Serializable
    @SerialName("recordings")
    data class SetRecordings(val on: Boolean) : SettingsAction

    /** Switched on, it also checks now. */
    @Serializable
    @SerialName("check_updates")
    data class SetCheckForUpdates(val on: Boolean) : SettingsAction

    @Serializable
    @SerialName("delete_all_chats")
    data object DeleteAllChats : SettingsAction
}

/**
 * What only the phone does for Settings: its other sections (connections, phone features), Android's prompts and
 * roles, and installing an update. A computer leaves each out, and the page says those are on the phone.
 */
interface SettingsPlatform {
    /** The phone's own sections between the model and the workspace: connections and phone features. */
    @Composable
    fun PhoneSections()

    /** Asks Android to let Whittle notify, when keeping tasks running is switched on; null where there's no asking. */
    val requestNotifications: (() -> Unit)?

    /** Whether Whittle is the phone's assistant, read again as the user comes back; null where there's no assistant role. */
    @Composable
    fun assistantHeld(): Boolean?

    /** Opens Android's choice of assistant. */
    fun chooseAssistant()

    /** Downloads and installs the newer version; null where it can't install (it's the phone's to update). */
    val installUpdate: (() -> Unit)?

    /** Keeps an update going through Android's own install setting. */
    @Composable
    fun UpdateEffects()
}

/** The screens Settings opens; each null where that screen isn't here yet. */
class SettingsNavigation(
    val back: () -> Unit,
    val changeModel: (() -> Unit)? = null,
    val linux: (() -> Unit)? = null,
    val memory: (() -> Unit)? = null,
    val automations: (() -> Unit)? = null,
)
