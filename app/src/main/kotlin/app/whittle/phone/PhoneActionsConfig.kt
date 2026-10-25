package app.whittle.phone

import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import app.whittle.reminders.Reminders
import app.whittle.tools.android.MapLinks
import app.whittle.tools.workspace.Workspaces
import kotlinx.serialization.Serializable

/** The phone tools' settings: where maps open, Whittle's own reminders, and what can be shared. */
@Serializable
data class PhoneActionsConfig(
    override val schemaVersion: Int,
    val maps: Maps,
    val reminders: Reminders,
    val share: Share,
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

    init {
        require(maps.searchUri.contains("{{query}}")) { "maps.searchUri must take {{query}}" }
        require(maps.directionsUri.contains("{{destination}}") && maps.directionsUri.contains("{{mode}}")) { "maps.directionsUri must take {{destination}} and {{mode}}" }
        require(switches.map { it.id }.toSet().size == switches.size) { "phone-actions.json switch ids must be unique" }
        require(offMessage.contains("{{feature}}")) { "offMessage must take {{feature}}" }
        require(reminders.maxReminders > 0 && reminders.maxTextChars > 0 && share.maxFileBytes > 0) { "phone-actions.json limits must be positive" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("phone-actions.json")
    }
}

/**
 * What the phone tools need from the app: their config, Whittle's reminders, the workspace files can be shared from,
 * and whether the user left each switch on.
 */
class PhoneTools(val config: PhoneActionsConfig, val reminders: Reminders, val workspaces: Workspaces, val isOn: (String) -> Boolean)
