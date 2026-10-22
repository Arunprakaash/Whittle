package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Focuses the chat on a project, a folder at the top of the workspace, making it if it isn't there; or, with no
 * name, back on the whole workspace. Commands then start there and relative paths resolve inside it.
 */
class UseProjectTool(workspaces: Workspaces, private val projects: ChatProjects) : WorkspaceTool(workspaces) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val name = arguments.text(NAME)?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        if (projects.isConfined(access.id)) {
            return error("This chat is limited to the project '${projects.projectOf(access.id)}'; only the user can change that")
        }
        if (name == null) {
            projects.choose(access.id, null)
            return success(buildJsonObject { put(PROJECT, null as String?) })
        }
        if (!ChatProjects.isValidName(name)) return error("'$name' isn't a plain folder name: use letters, digits, dots, dashes and underscores")
        val root = when (val folder = access.root()) {
            is Outcome.Failure -> return error(folder.error.message)
            is Outcome.Success -> folder.value
        }
        val folder = File(root, name)
        val created = !folder.exists()
        if (created && !withContext(Dispatchers.IO) { folder.mkdirs() }) return error("'$name' can't be created")
        if (!folder.isDirectory) return error("'$name' is a file, not a folder")
        projects.choose(access.id, name)
        return success(
            buildJsonObject {
                put(PROJECT, name)
                put(CREATED, created)
            },
        )
    }

    companion object {
        private const val NAME = "name"
        private const val PROJECT = "project"
        private const val CREATED = "created"

        val DEFINITION = ToolDefinition(
            id = ToolId("use_project"),
            description = "Sets the project this chat works on: a folder at the top of /workspace, made if it isn't there. " +
                "Commands then start in it and relative paths resolve inside it. Call it when you start a new project " +
                "or the user means an existing one, before working on it. With no name, the chat works on the whole " +
                "workspace again.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$NAME":{"type":"string","description":"The project's folder name; omit for the whole workspace"}}}""",
            ).jsonObject,
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_use_project",
            outputProvenance = Provenance.Trusted,
        )
    }
}
