package app.whittle.apps

import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable

/** How apps are built on the phone: the pinned toolchain, the build script, the app template and the powerful declarations (docs/native-apps.md). */
@Serializable
data class NativeAppsConfig(
    override val schemaVersion: Int,
    /** Every generated app's package starts with this. */
    val packagePrefix: String,
    val minSdk: Int,
    val targetSdk: Int,
    val toolchain: ToolchainPins,
    /** Runs in Linux in the project's folder; placeholders are `{{project}}`, `{{slug}}`, `{{versionCode}}`, `{{minSdk}}` and `{{targetSdk}}`. It doesn't sign. */
    val buildScript: String,
    val buildTimeoutSeconds: Int,
    /** How long the chat waits for the setup service to begin before it checks whether the toolchain is ready anyway. Not in the pins, so changing it doesn't trigger setup. */
    val setupStartTimeoutSeconds: Int,
    /** How many compiler errors go back to the agent. */
    val maxErrors: Int,
    /** How much of the compiler's output goes back to the agent. */
    val maxErrorChars: Int,
    /** How many signed APKs `build_app` keeps in a project's `build/`, newest first; older ones are deleted. */
    val keptApks: Int,
    /** How much of a failed setup's or removal's output is kept as its reason, for the agent and the card. */
    val maxReasonChars: Int,
    /** Paths under `native-app-template/` that `create_app` fills in and writes. */
    val template: List<String>,
    /** Declarations that let an app watch or control the rest of the phone, each with the sentence shown to the user. */
    val powerful: List<PowerfulDeclaration>,
    /** What each common permission means in plain words, for the card. */
    val permissionWords: List<PermissionWords>,
    /** Added to Whittle's application id to make the authority crash reports go to. */
    val crashAuthoritySuffix: String,
    /** The most characters of a crash's stack Whittle keeps, per app. */
    val maxCrashChars: Int,
    /** The launcher icon colours (`#RRGGBB`) `create_app` picks from, by a hash of the app's name. */
    val palette: List<String>,
    /** How long an install waits for the user to answer Android's prompt before it is cancelled and its session abandoned. */
    val installPromptTimeoutMinutes: Int,
    /** How old an open install session must be before a launch abandons it; longer than [installPromptTimeoutMinutes]. */
    val staleInstallSessionMinutes: Int,
) : VersionedConfig {
    init {
        require(packagePrefix.endsWith(".")) { "packagePrefix must end with a dot" }
        require(minSdk in 1..targetSdk) { "minSdk must not be above targetSdk" }
        require(buildTimeoutSeconds > 0 && toolchain.setupTimeoutSeconds > 0) { "Timeouts must be positive" }
        require(setupStartTimeoutSeconds > 0) { "setupStartTimeoutSeconds must be positive" }
        require(maxErrors > 0 && maxErrorChars > 0) { "Error limits must be positive" }
        require(keptApks >= 1) { "keptApks must keep at least the newest APK, which the card installs" }
        require(maxReasonChars > 0) { "maxReasonChars must be positive" }
        require(template.isNotEmpty()) { "template must name at least one file" }
        require(permissionWords.all { it.words.isNotBlank() }) { "permissionWords must have words for each permission" }
        require(permissionWords.map { it.name }.distinct().size == permissionWords.size) { "permissionWords names must be unique" }
        require(powerful.map { it.name }.distinct().size == powerful.size) { "powerful names must be unique" }
        require(crashAuthoritySuffix.startsWith(".")) { "crashAuthoritySuffix must start with a dot" }
        require(maxCrashChars > 0) { "maxCrashChars must be positive" }
        require(palette.isNotEmpty() && palette.all { COLOUR.matches(it) }) { "palette must hold #RRGGBB colours" }
        require(installPromptTimeoutMinutes > 0) { "installPromptTimeoutMinutes must be positive" }
        require(staleInstallSessionMinutes > installPromptTimeoutMinutes) {
            "staleInstallSessionMinutes must exceed installPromptTimeoutMinutes, so a launch never abandons a prompt still on screen"
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private val COLOUR = Regex("^#[0-9A-F]{6}$")
        val FILE = ConfigName("native-apps.json")
    }
}

/** What setup installs in Linux, each download pinned by its SHA-256. */
@Serializable
data class ToolchainPins(
    val aptPackages: List<String>,
    val downloads: List<PinnedDownload>,
    /** Installs everything and writes the ready marker last; `{{pinsHash}}` becomes the SHA-256 of these pins. */
    val setupScript: String,
    val setupTimeoutSeconds: Int,
)

@Serializable
data class PinnedDownload(
    val url: String,
    val sha256: String,
    /** Linux path the setup script saves it to. */
    val saveAs: String,
)

@Serializable
data class PowerfulDeclaration(
    /** The permission's full name. */
    val name: String,
    val kind: PowerfulKind,
    /** Plain words for what it lets the app do, shown on the card and before install. */
    val sentence: String,
)

/** [words] completes "Uses ..." for the permission [name]. */
@Serializable
data class PermissionWords(val name: String, val words: String)

@Serializable
enum class PowerfulKind { PERMISSION, SERVICE_PERMISSION }
