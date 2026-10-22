package app.whittle.settings

import app.whittle.design.theme.ThemeMode
import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable

/** App-level defaults shipped as config: appearance and on-device storage names. */
@Serializable
data class AppDefaults(
    override val schemaVersion: Int,
    val appearance: AppearanceDefaults,
    val storage: AppStorageNames,
    val terminal: TerminalDefaults,
    val chatList: ChatListDefaults,
    val siteIcons: SiteIconDefaults,
    val preview: PreviewDefaults,
    val background: BackgroundDefaults,
    val voice: VoiceDefaults,
    val toolRuntime: ToolRuntimeDefaults,
    val memory: MemoryDefaults,
) : VersionedConfig {
    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("app-defaults.json")
    }
}

@Serializable
data class AppearanceDefaults(
    val wallpaperColors: Boolean,
    val haptics: Boolean,
    /** System, Light or Dark. */
    val themeMode: String,
) {
    init {
        require(ThemeMode.entries.any { it.name == themeMode }) { "appearance.themeMode must be one of ${ThemeMode.entries}" }
    }

    val theme: ThemeMode get() = ThemeMode.valueOf(themeMode)
}

@Serializable
data class AppStorageNames(
    val connectionStoreName: String,
    val settingsStoreName: String,
    val runsDatabaseName: String,
    val chatsDatabaseName: String,
    val workspacesDirectoryName: String,
    /** Connector tool lists, kept so connected tools are there at start-up. Tokens live in the secret store. */
    val connectorsDirectoryName: String,
    /** The MCP servers the user added (names and addresses; their sign-ins are in the secret store). */
    val customConnectorsStoreName: String,
)

/** How many chats the sidebar shows before pointing to the Chats page. */
@Serializable
data class ChatListDefaults(val sidebarMaxChats: Int) {
    init {
        require(sidebarMaxChats > 0) { "sidebarMaxChats must be positive" }
    }
}

/**
 * Icons of the websites an answer cites or a step reads. They are fetched from each site itself, so
 * turning this off means the phone contacts no site on its own.
 */
@Serializable
data class SiteIconDefaults(
    val enabled: Boolean,
    /** Under the app's cache folder; Android may clear it when space runs low. */
    val cacheDirectoryName: String,
    /** Icons are stored and drawn at most this many pixels on their longest side. */
    val sizePx: Int,
    /** How much of a home page is read looking for its icon links. */
    val maxPageBytes: Int,
    val maxIconBytes: Int,
    val memoryEntries: Int,
) {
    init {
        require(sizePx > 0 && maxPageBytes > 0 && maxIconBytes > 0 && memoryEntries > 0) { "siteIcons limits must be positive" }
    }
}

/** Tasks keep running while the user is elsewhere, in a foreground service with an ongoing notification. */
@Serializable
data class BackgroundDefaults(val keepRunning: Boolean)

/** How a server the agent runs is shown in the live preview. */
@Serializable
data class PreviewDefaults(
    /** Height of the preview sheet as a share of the screen. */
    val sheetHeightFraction: Float,
) {
    init {
        require(sheetHeightFraction in 0.3f..1f) { "preview.sheetHeightFraction must be between 0.3 and 1" }
    }
}

/**
 * The programs that start and unpack Linux (proot, tar), laid out on first use from the APK. See
 * app/termux-runtimes.json for what's included.
 */
@Serializable
data class ToolRuntimeDefaults(
    /** Under the app's cache folder: proot's scratch space. */
    val cacheDirectoryName: String,
    /**
     * The environment those programs run with. {{prefix}} (where they live), {{dataDir}} (the app's data
     * folder), {{cacheDir}} (the scratch space) and {{systemPath}} (terminal.path) are filled in.
     */
    val environment: Map<String, String>,
)

/** The Android shell commands run in, and how each command gets its own process group. */
@Serializable
data class TerminalDefaults(val shell: String, val path: String)

/** Speaking instead of typing in the composer. */
@Serializable
data class VoiceDefaults(
    /** Recognize on the phone itself when it can, so no audio leaves it. */
    val preferOnDevice: Boolean,
    /** Silence that ends listening, as a hint to the recognizer (which reads it as an Int). */
    val silenceMillis: Int,
    /** After the user taps stop, how long to wait for the recognizer's last words before giving up on it. */
    val stopGraceMillis: Long,
    /** When the recognizer is still busy with the last session, how long to wait before trying once more. */
    val busyRetryMillis: Long,
)

/** Where the phone keeps what Whittle remembers (encrypted); the limits are the harness's (MemoryPolicy). */
@Serializable
data class MemoryDefaults(val fileName: String) {
    init {
        require(fileName.isNotBlank()) { "memory.fileName must be set" }
    }
}
