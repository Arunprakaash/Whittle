package app.whittle.settings

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
) : VersionedConfig {
    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("app-defaults.json")
    }
}

@Serializable
data class AppearanceDefaults(val wallpaperColors: Boolean, val haptics: Boolean)

@Serializable
data class AppStorageNames(
    val connectionStoreName: String,
    val runsDatabaseName: String,
    val chatsDatabaseName: String,
    val workspacesDirectoryName: String,
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

/** The Android shell commands run in, and how each command gets its own process group. */
@Serializable
data class TerminalDefaults(val shell: String, val path: String, val processGroupLauncher: String?)
