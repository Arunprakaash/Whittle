package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Puts an app from the workspace in front of the user as a card they can open and use. Only pages that
 * build_web_app produced are shown, so every app is a built React project, never hand-written HTML. The app
 * renders from the workspace, so a rebuild shows up live.
 */
class ShowArtifactTool(workspaces: Workspaces) : WorkspaceTool(workspaces) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val path = arguments.text(PATH)?.trim()?.takeIf { it.isNotEmpty() } ?: return error("path is missing")
        val title = arguments.text(TITLE)?.trim()?.takeIf { it.isNotEmpty() } ?: return error("title is missing")
        if (ENTRY_EXTENSIONS.none { path.lowercase().endsWith(it) }) return error("path must be an .html file")
        val file = when (val resolved = access.file(path)) {
            is Outcome.Failure -> return error(resolved.error.message)
            is Outcome.Success -> resolved.value
        }
        if (!file.isFile) return error("'$path' doesn't exist yet; build the app first")
        if (!BuiltPages.wasBuilt(file)) {
            return error("'$path' wasn't made by build_web_app. Apps are React projects: write src/main.tsx and its components, build with build_web_app, then show the page it returns")
        }
        return success(buildJsonObject { put(PATH, path); put(TITLE, title) })
    }

    companion object {
        const val PATH = "path"
        const val TITLE = "title"
        private val ENTRY_EXTENSIONS = listOf(".html", ".htm")

        val DEFINITION = ToolDefinition(
            id = ToolId("show_artifact"),
            description = "Shows an app to the user as a card they can open, use and share. Pass the page build_web_app " +
                "returned (e.g. dist/index.html); other pages are refused. Call it again only for a different app.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$PATH":{"type":"string","description":"Entry page relative to the workspace, e.g. index.html"},""" +
                    """"$TITLE":{"type":"string","description":"Short name the user sees, e.g. Tip calculator"}""" +
                    """},"required":["$PATH","$TITLE"]}""",
            ).jsonObject,
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_show_artifact",
            outputProvenance = Provenance.Trusted,
        )
    }
}
