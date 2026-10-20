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
 * Puts a web page or app from the workspace in front of the user as a card they can open and use. It only
 * checks the entry file exists; the app renders it from the workspace, so later edits show up live.
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
        if (!file.isFile) return error("'$path' doesn't exist yet; write it first")
        return success(buildJsonObject { put(PATH, path); put(TITLE, title) })
    }

    companion object {
        const val PATH = "path"
        const val TITLE = "title"
        private val ENTRY_EXTENSIONS = listOf(".html", ".htm")

        val DEFINITION = ToolDefinition(
            id = ToolId("show_artifact"),
            description = "Shows an HTML page or web app from this chat's workspace to the user as a card they can open, " +
                "use and share. Write the files first, then call this with the entry page. Call it again only for a different page.",
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
