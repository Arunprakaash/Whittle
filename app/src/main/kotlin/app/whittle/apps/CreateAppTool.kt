package app.whittle.apps

import app.whittle.agent.AgentMessages
import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import app.whittle.tools.workspace.ChatProjects
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.text.Normalizer

/**
 * Starts a new app: writes a project from the template into the workspace, in a folder named for the app, and
 * focuses the chat on it. The folder and the package get the same number when a name is taken (counter, counter2).
 * [template] gives the text of a template file by its path; [isInstalled] says whether a package is on the phone.
 */
class CreateAppTool(
    private val workspaces: Workspaces,
    private val projects: ChatProjects,
    private val config: NativeAppsConfig,
    private val whittlePackage: String,
    private val messages: AgentMessages,
    private val isInstalled: (String) -> Boolean,
    private val template: (String) -> String,
) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val chat = context.workspace ?: return reply(messages.appNoWorkspace, error = true)
        val name = (arguments[NAME] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().replace(WHITESPACE, " ")
        if (name.isEmpty()) return reply(messages.appNameEmpty, error = true)
        val root = when (val folder = workspaces.folder()) {
            is Outcome.Failure -> return failed(folder.error.message)
            is Outcome.Success -> folder.value
        }
        val slug = slugFor(name)
        val unique = (1..Int.MAX_VALUE).asSequence()
            .map { if (it == 1) slug else "$slug$it" }
            .first { !isInstalled(config.packagePrefix + it) && !File(root, it).exists() }
        val folder = File(root, unique)
        val project = AppProject(name, config.packagePrefix + unique, versionCode = 0)
        try {
            withContext(Dispatchers.IO) { write(folder, project) }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { folder.deleteRecursively() }
            if (e is CancellationException) throw e
            return failed(e.message ?: e.javaClass.simpleName)
        }
        projects.choose(chat, unique)
        return reply(messages.appCreated(name, unique, project.packageName))
    }

    private fun write(folder: File, project: AppProject) {
        val values = mapOf(
            "name" to project.name,
            "package" to project.packageName,
            "whittlePackage" to whittlePackage,
            "crashAuthority" to whittlePackage + config.crashAuthoritySuffix,
            "colour" to config.palette[Math.floorMod(project.name.hashCode(), config.palette.size)],
        )
        config.template.forEach { path ->
            val target = File(folder, path)
            target.parentFile?.mkdirs()
            target.writeText(render(path, template(path), values))
        }
        AppProjects.write(folder, project)
    }

    /** Fills the placeholders in one pass, so a name that looks like a placeholder stays as typed; the name is escaped for the file it lands in. */
    private fun render(path: String, text: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(text) { match ->
            val key = match.groupValues[1]
            val value = values[key] ?: return@replace match.value
            if (key == NAME) escapeName(path, value) else value
        }

    private fun escapeName(path: String, name: String): String {
        if (!path.endsWith(".xml")) return name
        val resource = if (path.startsWith("res/values/")) androidString(name) else name
        return resource.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    /** aapt reads backslashes, quotes and a leading @ or ? in a string resource as syntax. */
    private fun androidString(name: String): String {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        return if (escaped.startsWith("@") || escaped.startsWith("?")) "\\$escaped" else escaped
    }

    private fun failed(detail: String) = reply(messages.toolFailed(DEFINITION.id.value, detail), error = true)

    private fun reply(text: String, error: Boolean = false) = ToolOutput(text, isError = error, provenance = Provenance.Trusted)

    companion object {
        private const val NAME = "name"
        private val WHITESPACE = Regex("\\s+")
        private val PLACEHOLDER = Regex("\\{\\{(\\w+)\\}\\}")

        val DEFINITION = ToolDefinition(
            id = ToolId("create_app"),
            description = "Starts a new Android app: writes a Kotlin project from a template into a folder at the top of " +
                "/workspace, named for the app, and switches this chat to it. Then edit the files with the file tools and " +
                "build it with build_app. Use it when the user asks you to make an app.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"name":{"type":"string","description":"The app's name as the user sees it"}},"required":["name"]}""",
            ).jsonObject,
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_create_app",
            outputProvenance = Provenance.Trusted,
        )
    }
}

private const val MAX_SLUG = 40

private val KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do",
    "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
    "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
    "true", "false", "null", "_",
    "as", "fun", "in", "is", "object", "typealias", "typeof", "val", "var", "when",
)

/** A folder and package segment for [name]: lowercase Latin letters (accents dropped), digits and single underscores, valid as a Java identifier. */
fun slugFor(name: String): String {
    val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    var slug = plain.map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")
        .replace(Regex("_+"), "_").trim('_')
    if (slug.isEmpty()) return "app"
    if (slug[0].isDigit() || slug in KEYWORDS) slug = "app_$slug"
    return slug.take(MAX_SLUG).trimEnd('_')
}
