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
    val artifacts: ArtifactDefaults,
    val background: BackgroundDefaults,
    val export: ExportDefaults,
    val toolRuntime: ToolRuntimeDefaults,
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

/** Turning an app the agent built into its own installable Android app. */
@Serializable
data class ExportDefaults(
    /** Exported apps get <packagePrefix>.<name>.<id>; the id is stable for the same app, so re-exports update it. */
    val packagePrefix: String,
    val maxNameChars: Int,
    val maxLabelChars: Int,
    /** A title is cut before the first of these for the home-screen name, so subtitles don't take the space. */
    val labelSeparators: List<String>,
    val signingKeyAlias: String,
    val certificateSubject: String,
    val certificateValidityYears: Long,
    val iconSizePx: Int,
    /** Icon backgrounds; each app gets one picked from its id. */
    val iconColors: List<String>,
    val maxAppBytes: Long,
    /** Under the app's cache folder: where an app is packaged and signed before installing. */
    val workDirectoryName: String,
) {
    init {
        require(iconColors.isNotEmpty()) { "export.iconColors must not be empty" }
        require(maxNameChars > 0 && maxLabelChars > 0 && iconSizePx > 0 && maxAppBytes > 0) { "export limits must be positive" }
    }
}

/** Tasks keep running while the user is elsewhere, in a foreground service with an ongoing notification. */
@Serializable
data class BackgroundDefaults(val keepRunning: Boolean)

/** How web apps the agent builds are shown, opened elsewhere and shared. */
@Serializable
data class ArtifactDefaults(
    /**
     * Each chat's apps load from `https://<chat>.<domainSuffix>/`, served by Whittle itself, never the network.
     * A per-chat origin keeps one chat's app from reading another's saved data.
     */
    val domainSuffix: String,
    /** Under the app's cache folder; must match the cache-path in res/xml/file_paths.xml. */
    val sharedDirectoryName: String,
    /** Height of the preview sheet as a share of the screen. */
    val sheetHeightFraction: Float,
    /** Local files larger than this stay linked instead of being inlined when an app is bundled into one file. */
    val maxInlineBytes: Long,
) {
    init {
        require(sheetHeightFraction in 0.3f..1f) { "artifacts.sheetHeightFraction must be between 0.3 and 1" }
        require(maxInlineBytes > 0) { "artifacts.maxInlineBytes must be positive" }
    }
}

/**
 * The command-line tools that ship with the app (Node and npm, Python and pip, git), laid out on first use
 * from the APK. See app/termux-runtimes.json for what's included.
 */
@Serializable
data class ToolRuntimeDefaults(
    /** Off: commands get only Android's own shell tools. */
    val enabled: Boolean,
    /** Under the app's cache folder: package managers' download caches, shared by every chat. */
    val cacheDirectoryName: String,
    /**
     * Added to every command's environment. {{prefix}} (where the tools live), {{dataDir}} (the app's data
     * folder), {{cacheDir}} (the shared caches) and {{systemPath}} (terminal.path) are filled in.
     */
    val environment: Map<String, String>,
    /**
     * Shell functions defined before every command, with the same values filled in. Android only lets an app
     * start programs from its native-library folder, so tools that are scripts (npm, npx) are started through
     * their interpreter here instead of from PATH.
     */
    val shellFunctions: List<String>,
)

/** The Android shell commands run in, and how each command gets its own process group. */
@Serializable
data class TerminalDefaults(val shell: String, val path: String)
