package app.whittle.tools.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Where maps open: [searchUri] shows a place (`{{query}}`), [directionsUri] gives directions (`{{destination}}`, and
 * `{{mode}}`, one of the tool's travel modes). From config, so another maps app or link format needs no code change.
 */
data class MapLinks(val searchUri: String, val directionsUri: String)

/** Opens a place, or directions to it, in the phone's maps app; the user starts navigation there. */
class OpenMapTool(private val context: Context, private val links: MapLinks) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val place = arguments.text(PLACE)
        if (place.isEmpty()) return ToolJson.error("place is empty")
        val directions = arguments.text(DIRECTIONS).toBooleanStrictOrNull() ?: false
        val mode = arguments.text(MODE).ifEmpty { TRAVEL_MODES.first() }
        if (mode !in TRAVEL_MODES) return ToolJson.error("mode must be one of $TRAVEL_MODES")
        val uri = if (directions) {
            links.directionsUri.replace(DESTINATION_HOLE, Uri.encode(place)).replace(MODE_HOLE, Uri.encode(mode))
        } else {
            links.searchUri.replace(QUERY_HOLE, Uri.encode(place))
        }
        if (!this.context.startHandOff(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))) return ToolJson.error("no maps app on this phone opens it")
        return ToolJson.success(Provenance.Trusted, buildJsonObject { put(OPENED, true); put(DIRECTIONS, directions) })
    }

    companion object {
        private const val PLACE = "place"
        private const val DIRECTIONS = "directions"
        private const val MODE = "mode"
        private const val OPENED = "openedInMaps"
        private const val QUERY_HOLE = "{{query}}"
        private const val DESTINATION_HOLE = "{{destination}}"
        private const val MODE_HOLE = "{{mode}}"
        private val TRAVEL_MODES = listOf("driving", "walking", "bicycling", "transit")

        val DEFINITION = ToolDefinition(
            id = ToolId("open_map"),
            description = "Opens a place in the phone's maps app, or directions to it. The user starts navigation there.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$PLACE":{"type":"string","description":"An address or a place name, e.g. Smile Dental, Indiranagar"},""" +
                    """"$DIRECTIONS":{"type":"boolean","description":"True for directions from where the user is"},""" +
                    """"$MODE":{"type":"string","enum":${Json.encodeToString(TRAVEL_MODES)},"description":"How they travel, for directions"}""" +
                    """},"required":["$PLACE"]}""",
            ).jsonObject,
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_open_map",
            outputProvenance = Provenance.Trusted,
        )
    }
}

/**
 * Opens the phone's dialer with a number filled in. It never calls: the user taps call there, so Whittle needs no phone
 * permission. A config rule makes it always show a decision card.
 */
class CallTool(private val context: Context) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val phone = arguments.text(PHONE_NUMBER)
        if (!PHONE_PATTERN.matches(phone)) return ToolJson.error("phoneNumber is not a phone number")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse(TEL_SCHEME + Uri.encode(phone)))
        if (!this.context.startHandOff(intent)) return ToolJson.error("no phone app is installed")
        return ToolJson.success(Provenance.Trusted, buildJsonObject { put(OPENED, true); put(CALLED, false) })
    }

    companion object {
        private const val NAME = "name"
        private const val PHONE_NUMBER = "phoneNumber"
        private const val OPENED = "openedInDialer"
        private const val CALLED = "called"
        private const val TEL_SCHEME = "tel:"
        private val PHONE_PATTERN = Regex("""^\+?[0-9 ()\-.]{3,}$""")

        val DEFINITION = ToolDefinition(
            id = ToolId("call"),
            description = "Opens the phone's dialer with a number ready to call. Nothing happens until the user taps call " +
                "there. Look the number up with contacts_search first.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$NAME":{"type":"string","description":"Who it is, as the user knows them"},""" +
                    """"$PHONE_NUMBER":{"type":"string","description":"Phone number"}""" +
                    """},"required":["$NAME","$PHONE_NUMBER"]}""",
            ).jsonObject,
            // A number handed to the dialer on this phone; the user does the calling.
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_call",
            outputProvenance = Provenance.Trusted,
        )
    }
}

/** Opens an app on the phone by its name, as the launcher shows it. */
class OpenAppTool(private val context: Context) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val name = arguments.text(APP)
        if (name.isEmpty()) return ToolJson.error("app is empty")
        val apps = withContext(Dispatchers.IO) { this@OpenAppTool.context.launchableApps(includeThisApp = false) }
        val label = AppMatch.find(name, apps.keys).singleOrNull() ?: return AppMatch.notChosen(name, apps.keys, OPENED)
        val launch = this.context.packageManager.getLaunchIntentForPackage(apps.getValue(label)) ?: return ToolJson.error("$label can't be opened")
        if (!this.context.startHandOff(launch)) return ToolJson.error("$label can't be opened")
        return ToolJson.success(Provenance.Trusted, buildJsonObject { put(OPENED, true); put(APP, label) })
    }

    companion object {
        private const val APP = "app"
        private const val OPENED = "opened"

        val DEFINITION = ToolDefinition(
            id = ToolId("open_app"),
            description = "Opens an app on the phone by the name its icon shows. When several match, or none, it lists " +
                "names to choose from.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$APP":{"type":"string","description":"The app's name, e.g. Spotify"}},"required":["$APP"]}""",
            ).jsonObject,
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_open_app",
            outputProvenance = Provenance.Trusted,
        )
    }
}

/** Matches what the user calls an app to the launcher's names; pure, so it's tested on the JVM. */
internal object AppMatch {
    /** An exact name (ignoring case) wins; otherwise every name that contains [query]. */
    fun find(query: String, names: Collection<String>): List<String> {
        val wanted = query.trim().lowercase()
        names.filter { it.lowercase() == wanted }.takeIf { it.isNotEmpty() }?.let { return it.sorted() }
        return names.filter { wanted in it.lowercase() }.sorted()
    }

    /** A few names sharing the most words with [query], for the model to pick from when nothing matched. */
    fun closest(query: String, names: Collection<String>): List<String> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }.toSet()
        return names.map { it to it.lowercase().split(' ').count { word -> word in words } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(MAX_SUGGESTIONS).map { it.first }
    }

    /** What a tool tells the model when [query] named no single app: the names that matched, or the closest when none did. */
    fun notChosen(query: String, names: Collection<String>, openedKey: String): ToolOutput {
        val matches = find(query, names)
        return ToolJson.success(
            Provenance.Trusted,
            buildJsonObject {
                put(openedKey, false)
                putJsonArray(if (matches.isEmpty()) NO_MATCH else SEVERAL) {
                    matches.ifEmpty { closest(query, names) }.forEach { add(JsonPrimitive(it)) }
                }
            },
        )
    }

    private const val MAX_SUGGESTIONS = 5
    private const val SEVERAL = "severalMatch"
    private const val NO_MATCH = "noneMatchClosest"
}

/** Apps with a launcher icon, by the name the launcher shows, and their package names. */
internal fun Context.launchableApps(includeThisApp: Boolean): Map<String, String> =
    packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { includeThisApp || it.activityInfo.packageName != packageName }
        .associate { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }

/** Opens a web page in the user's browser. The address leaves the phone, so it is asked about like other outbound tools. */
class OpenLinkTool(private val context: Context) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val url = arguments.text(URL)
        val uri = Uri.parse(url)
        if (uri.scheme != HTTPS || uri.host.isNullOrEmpty()) return ToolJson.error("url must be an https address")
        if (!this.context.startHandOff(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))) {
            return ToolJson.error("no browser on this phone opens it")
        }
        return ToolJson.success(Provenance.Trusted, buildJsonObject { put(OPENED, true) })
    }

    companion object {
        private const val URL = "url"
        private const val OPENED = "opened"
        private const val HTTPS = "https"

        val DEFINITION = ToolDefinition(
            id = ToolId("open_link"),
            description = "Opens a web page in the user's browser, or in the app that handles that address.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$URL":{"type":"string","description":"An https address"}},"required":["$URL"]}""",
            ).jsonObject,
            riskTier = RiskTier.NETWORK_OUT,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_open_link",
            outputProvenance = Provenance.Trusted,
        )
    }
}
