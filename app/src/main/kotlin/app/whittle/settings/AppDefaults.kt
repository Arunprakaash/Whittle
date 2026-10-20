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

/** The Android shell commands run in, and how each command gets its own process group. */
@Serializable
data class TerminalDefaults(val shell: String, val path: String, val processGroupLauncher: String?)
