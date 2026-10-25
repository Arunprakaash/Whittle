package app.whittle.phone

import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import app.whittle.reminders.Reminders
import app.whittle.tools.android.DeleteFilesLimits
import app.whittle.tools.android.HealthConnect
import app.whittle.tools.android.HealthLimits
import app.whittle.tools.android.FileTypes
import app.whittle.tools.android.FindFilesLimits
import app.whittle.tools.android.MapLinks
import app.whittle.tools.android.StorageLimits
import app.whittle.tools.workspace.Workspaces
import kotlinx.serialization.Serializable

/** The phone tools' settings: where maps open, Whittle's own reminders, what can be shared, reading health data, and storage. */
@Serializable
data class PhoneActionsConfig(
    override val schemaVersion: Int,
    val maps: Maps,
    val reminders: Reminders,
    val share: Share,
    val health: Health,
    val storage: Storage,
    /** What the user can switch off in Settings › On this phone, each with the tools it stops. */
    val switches: List<Switch>,
    /** What a switched-off tool tells the model; `{{feature}}` is the switch's title. */
    val offMessage: String,
) : VersionedConfig {
    /**
     * A phone feature the user can switch off. [capabilities] are what Android must grant before it works, asked when
     * it's switched on; [tools] are refused while it's off.
     */
    @Serializable
    data class Switch(val id: String, val icon: String, val title: String, val capabilities: List<String>, val tools: List<String>)

    /** [searchUri] takes `{{query}}`; [directionsUri] takes `{{destination}}` and `{{mode}}`. */
    @Serializable
    data class Maps(val searchUri: String, val directionsUri: String) {
        val links: MapLinks get() = MapLinks(searchUri, directionsUri)
    }

    /** Kept encrypted in [fileName] under the app's files folder; at most [maxReminders] waiting at once. */
    @Serializable
    data class Reminders(val fileName: String, val maxReminders: Int, val maxTextChars: Int)

    /** A file is shared from a copy in [directoryName] under the cache, never from the workspace itself. */
    @Serializable
    data class Share(val directoryName: String, val maxFileBytes: Long)

    /**
     * Health Connect: before Android 14 it's [providerPackage] from Play, opened with [installUri] (`{{package}}`). Reads
     * cover [defaultDays] unless the model says, at most [maxDays] (or [maxHourlyDays] by hour), and [maxRecords] rows.
     */
    @Serializable
    data class Health(
        val providerPackage: String,
        val installUri: String,
        val defaultDays: Int,
        val maxDays: Int,
        val maxHourlyDays: Int,
        val maxRecords: Int,
    ) {
        val limits: HealthLimits get() = HealthLimits(defaultDays, maxDays, maxHourlyDays)

        init {
            require(providerPackage.isNotBlank() && installUri.contains("{{package}}")) { "health.installUri must take {{package}}" }
            require(maxRecords > 0) { "health.maxRecords must be positive" }
            // Builds the limits once, so bad day counts fail when the config loads.
            limits
        }
    }

    /**
     * The storage tools: [maxFolders] listed by the overview, scans stop after [scanMillis], lists hold [maxResults],
     * [oldDays] is "old", at most [maxHashBytes] are read looking for copies, and [types] maps each kind of file to its extensions.
     * One delete takes at most [maxDeletePerCall] files, so its card stays short enough to read; [notDeleted] marks a
     * file on that card that won't go.
     */
    @Serializable
    data class Storage(
        val maxFolders: Int,
        val scanMillis: Long,
        val maxResults: Int,
        val oldDays: Int,
        val maxHashBytes: Long,
        val maxDeletePerCall: Int,
        val notDeleted: String,
        val types: Map<String, List<String>>,
    ) {
        val limits: StorageLimits get() = StorageLimits(maxFolders, scanMillis, oldDays)
        val deleteLimits: DeleteFilesLimits get() = DeleteFilesLimits(maxDeletePerCall, notDeleted)
        val findLimits: FindFilesLimits get() = FindFilesLimits(maxResults, scanMillis, maxHashBytes)
        val fileTypes: FileTypes get() = FileTypes(types)
    }

    init {
        require(maps.searchUri.contains("{{query}}")) { "maps.searchUri must take {{query}}" }
        require(maps.directionsUri.contains("{{destination}}") && maps.directionsUri.contains("{{mode}}")) { "maps.directionsUri must take {{destination}} and {{mode}}" }
        require(switches.map { it.id }.toSet().size == switches.size) { "phone-actions.json switch ids must be unique" }
        require(offMessage.contains("{{feature}}")) { "offMessage must take {{feature}}" }
        require(reminders.maxReminders > 0 && reminders.maxTextChars > 0 && share.maxFileBytes > 0) { "phone-actions.json limits must be positive" }
        require(storage.maxFolders > 0 && storage.scanMillis > 0 && storage.maxResults > 0 && storage.oldDays > 0 && storage.maxHashBytes > 0 && storage.maxDeletePerCall > 0) { "phone-actions.json storage limits must be positive" }
        require(storage.maxDeletePerCall <= MAX_DELETE_PER_CALL && storage.notDeleted.isNotBlank()) { "phone-actions.json storage.maxDeletePerCall must be at most $MAX_DELETE_PER_CALL and notDeleted must say something" }
        require(storage.types.keys == STORAGE_TYPES) { "phone-actions.json storage.types must list exactly $STORAGE_TYPES" }
    }

    companion object {
        /** A delete card lists every file, so more than this wouldn't fit on one to read. */
        private const val MAX_DELETE_PER_CALL = 50
        private val STORAGE_TYPES = setOf("image", "video", "audio", "document", "apk", "archive")
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("phone-actions.json")
    }
}

/**
 * What the phone tools need from the app: their config, Whittle's reminders, the workspace files can be shared from,
 * Health Connect, and whether the user left each switch on.
 */
class PhoneTools(val config: PhoneActionsConfig, val reminders: Reminders, val workspaces: Workspaces, val health: HealthConnect, val isOn: (String) -> Boolean)
