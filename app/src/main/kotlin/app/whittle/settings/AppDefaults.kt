package app.whittle.settings

import app.whittle.design.theme.ThemeMode
import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import app.whittle.tools.api.RiskTier
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
    val projects: ProjectDefaults,
    val files: FilesDefaults,
    val background: BackgroundDefaults,
    val voice: VoiceDefaults,
    val toolRuntime: ToolRuntimeDefaults,
    val memory: MemoryDefaults,
    val attachments: AttachmentDefaults,
    val updates: UpdateDefaults,
    val starters: List<Starter>,
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
    /** The workspace every chat shares, mounted at /workspace in Linux. */
    val workspaceDirectoryName: String,
    /** Connector tool lists, kept so connected tools are there at start-up. Tokens live in the secret store. */
    val connectorsDirectoryName: String,
    /** The MCP servers the user added (names and addresses; their sign-ins are in the secret store). */
    val customConnectorsStoreName: String,
    /** Under the app's files folder: the project each chat works on. */
    val chatProjectsFileName: String,
    /** The user's automations, encrypted. */
    val automationsFileName: String,
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

/**
 * Checking GitHub's Releases for a newer Whittle. [check] is the default of the user's switch in Settings; [latestReleaseUrl]
 * is GitHub's latest-release endpoint, asked at most every [everyHours]; [apkPattern] picks the APK among a release's files.
 * What the last check found is kept in [fileName], under the app's cache folder.
 */
@Serializable
data class UpdateDefaults(val check: Boolean, val latestReleaseUrl: String, val apkPattern: String, val everyHours: Int, val fileName: String) {
    init {
        require(latestReleaseUrl.startsWith("https://")) { "updates.latestReleaseUrl must be https" }
        require(everyHours > 0) { "updates.everyHours must be positive" }
        Regex(apkPattern)
    }
}

/** Tasks keep running while the user is elsewhere, in a foreground service with an ongoing notification. */
@Serializable
data class BackgroundDefaults(val keepRunning: Boolean, val approvals: NotificationApprovalDefaults)

/**
 * Approval cards that appear while the user is away. With [fromNotification] on, their notification carries the card's own
 * Approve and Decline buttons, which work only once the phone is unlocked. Calls at an [inAppOnly] risk tier never get
 * buttons: the notification opens the chat, and the user decides there.
 */
@Serializable
data class NotificationApprovalDefaults(val fromNotification: Boolean, val inAppOnly: Set<String>) {
    init {
        require(inAppOnly.all { name -> RiskTier.entries.any { it.name == name } }) { "background.approvals.inAppOnly must name tiers from ${RiskTier.entries}" }
    }

    /** Whether a waiting call at [tier] may be answered from its notification. */
    fun buttonsFor(tier: RiskTier): Boolean = fromNotification && tier.name !in inAppOnly
}

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
data class MemoryDefaults(
    val fileName: String,
    /** Kinds of thing to remember, offered while there are none; each opens the editor started for it. */
    val ideas: List<MemoryIdea> = emptyList(),
) {
    init {
        require(fileName.isNotBlank()) { "memory.fileName must be set" }
    }
}

/** A kind of thing worth remembering: what it's about, an example, and the words the editor starts with. */
@Serializable
data class MemoryIdea(val icon: String, val title: String, val example: String, val start: String)

/**
 * A task the first chat offers, one that works right away: no Linux, no connection. Tapping sends [prompt] as the user's
 * message. One that needs the phone feature [feature] (an id in phone-actions.json) isn't offered while it's switched off.
 */
@Serializable
data class Starter(val icon: String, val title: String, val prompt: String, val feature: String? = null)

/**
 * Photos and files the user adds to a message: where in the workspace they're saved (inside the chat's project when
 * it has one), how many and how big, and how photos are sized for the model and for the composer's thumbnails.
 */
@Serializable
data class AttachmentDefaults(
    val directoryName: String,
    val maxPerMessage: Int,
    val maxFileBytes: Long,
    /** A photo's longer side when sent to a model; larger photos are scaled down, smaller ones kept as they are. */
    val imageMaxSidePx: Int,
    val imageJpegQuality: Int,
    val thumbnailSidePx: Int,
    /** Prepared photos and thumbnails kept in memory, so scrolling and each model call don't decode them again. */
    val memoryEntries: Int,
    /** Under the app's cache: where the camera saves a photo before it's added. */
    val cameraDirectoryName: String,
) {
    init {
        require(directoryName.isNotBlank() && '/' !in directoryName && cameraDirectoryName.isNotBlank()) { "attachments directories must be plain names" }
        require(maxPerMessage > 1 && maxFileBytes > 0) { "attachments.maxPerMessage must be at least 2 and maxFileBytes positive" }
        require(imageMaxSidePx > 0 && thumbnailSidePx > 0 && memoryEntries > 0 && imageJpegQuality in 1..100) { "attachments image sizes must be positive" }
    }
}

/** The file browser: what it hides, how much it shows, how it marks changes and colours code. */
@Serializable
data class FilesDefaults(
    /** Under the app's files folder: what the agent changed in each chat, kept for Changes. */
    val changesDirectoryName: String,
    /** Names never listed, such as Whittle's own folder of saved tool outputs. */
    val hiddenNames: List<String>,
    /** Longer files open as their start, with a note. */
    val maxPreviewChars: Int,
    val maxImageSidePx: Int,
    /** Unchanged lines kept around each change; longer runs fold. */
    val diffContextLines: Int,
    /** Past this many line pairs, a change shows as all removed then all added. */
    val maxDiffCells: Int,
    val folderIcon: String,
    val defaultFileIcon: String,
    /** By extension, lower case. */
    val fileIcons: Map<String, String>,
    val imageExtensions: List<String>,
    /** Prose, and files without an extension, show without code colours. */
    val proseExtensions: List<String>,
    /** Shown as their pages. */
    val pdfExtensions: List<String>,
    /** Coloured as keywords in code. */
    val keywords: List<String>,
    /** Line comment marker to the extensions that use it. */
    val lineComments: Map<String, List<String>>,
) {
    init {
        require(maxPreviewChars > 0 && maxImageSidePx > 0 && diffContextLines >= 0 && maxDiffCells > 0) { "files limits must be positive" }
    }

    fun iconFor(name: String, directory: Boolean): String =
        if (directory) folderIcon else fileIcons[name.substringAfterLast('.', "").lowercase()] ?: defaultFileIcon

    fun commentFor(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return lineComments.entries.firstOrNull { extension in it.value }?.key
    }

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in imageExtensions

    fun isPdf(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in pdfExtensions

    fun isCode(name: String): Boolean = name.substringAfterLast('.', "").lowercase().let { it.isNotEmpty() && it !in proseExtensions }
}

/** A chat's project: how the agent is told about it, and how many recent ones a new chat offers. */
@Serializable
data class ProjectDefaults(
    /** {{project}} is the folder name, {{mount}} where the workspace is in Linux. */
    val promptWithProject: String,
    /** {{mount}} is where the workspace is in Linux. */
    val promptWithoutProject: String,
    val recentCount: Int,
) {
    init {
        require(recentCount >= 0) { "projects.recentCount can't be negative" }
    }

    fun prompt(project: String?, mount: String): String =
        (if (project == null) promptWithoutProject else promptWithProject.replace(PROJECT, project)).replace(MOUNT, mount)

    private companion object {
        const val PROJECT = "{{project}}"
        const val MOUNT = "{{mount}}"
    }
}
