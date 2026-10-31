package app.whittle.tools.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Opens Android's settings page for an app, where the user clears its cache or data or uninstalls it. Whittle never does
 * either itself: another app's storage is the user's to clear, on the page Android provides for it.
 */
class OpenAppStorageTool(private val context: Context) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val name = arguments.text(APP)
        if (name.isEmpty()) return ToolJson.error("app is empty")
        val apps = withContext(Dispatchers.IO) { this@OpenAppStorageTool.context.launchableApps(includeThisApp = true) }
        val label = AppMatch.find(name, apps.keys).singleOrNull() ?: return AppMatch.notChosen(name, apps.keys, OPENED)
        val page = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts(PACKAGE_SCHEME, apps.getValue(label), null))
        if (!this.context.startHandOff(page)) return ToolJson.error("$label's settings page can't be opened")
        return ToolJson.success(Provenance.Trusted, buildJsonObject { put(OPENED, true); put(APP, label) })
    }

    companion object {
        private const val APP = "app"
        private const val OPENED = "opened"
        private const val PACKAGE_SCHEME = "package"

        val DEFINITION = ToolDefinition(
            id = ToolId("open_app_storage"),
            description = "Opens an app's settings page on the phone, where the user can see its storage and clear its cache or " +
                "data. Nothing is cleared until they do it there. When several apps match, or none, it lists names to choose from.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$APP":{"type":"string","description":"The app's name, e.g. Instagram"}},"required":["$APP"]}""",
            ).jsonObject,
            // Only opens a page; the user does the clearing.
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_open_app_storage",
            outputProvenance = Provenance.Trusted,
        )
    }
}
