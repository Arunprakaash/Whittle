package app.whittle.tools.workspace

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.config.PromptTemplate
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException

/**
 * Bundles a TypeScript or React project the agent wrote in the workspace into a web app, the way a bundler
 * would on a computer: TypeScript and JSX are compiled, local imports and CSS are followed, and npm packages
 * download from the package CDN. The entry is either the app's page (whose local script tags are bundled) or
 * one script (which gets a page from [pageTemplate]). The page and bundles land in the output folder, and the
 * app appears in the chat as a card under [NAME]. Errors in the project come back to the agent so it can fix
 * them and build again.
 */
class BuildWebAppTool(
    workspaces: Workspaces,
    private val bundler: WebBundler,
    private val config: WebBuildConfig,
    private val pageTemplate: PromptTemplate,
) : WorkspaceTool(workspaces) {
    override val definition = DEFINITION
    private val cdn = PackageCdn(config.packages)

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val entry = arguments.text(ENTRY)?.trim()?.trimStart('/')?.takeIf { it.isNotEmpty() } ?: return error("entry is missing")
        val outDir = (arguments.text(OUT_DIR)?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: config.defaultOutDir.trim('/'))
        val name = arguments.text(NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: return error("name is missing")
        val title = arguments.text(TITLE)?.trim()?.takeIf { it.isNotEmpty() } ?: name
        val root = access.root().orElse { return error(it) }.canonicalFile
        val entryFile = access.file(entry).orElse { return error(it) }
        if (!entryFile.isFile) return error("'$entry' doesn't exist yet; write it first")
        val outFolder = access.file(outDir).orElse { return error(it) }
        if (outFolder == root) return error("outDir must be a folder inside the workspace, e.g. ${config.defaultOutDir}")
        val outPath = outFolder.relativeTo(root).invariantSeparatorsPath
        if (entryFile.startsWith(outFolder)) return error("'$entry' is inside the output folder '$outPath'; keep sources outside it")

        val (page, pageName) = withContext(Dispatchers.IO) { page(entry, entryFile, title) } ?: return error(
            "entry must be a page (${config.pageExtensions.joinToString()}) or a script (${config.entryExtensions.joinToString()})",
        )
        val scripts = page.scripts(config.entryExtensions)
        if (scripts.isEmpty()) return error("'$entry' loads no local ${config.entryExtensions.joinToString("/")} script to build")

        val files = withContext(Dispatchers.IO) { sourceFiles(root, outFolder) } ?: return error(
            "the workspace has more than ${config.maxSourceFiles} files; remove ones the app doesn't need",
        )
        val missing = scripts.filterNot { it in files }
        if (missing.isNotEmpty()) return error("the page loads ${missing.joinToString()}, which doesn't exist yet")

        val entryPoints = entryPoints(scripts)
        val manifest = withContext(Dispatchers.IO) { File(root, config.packages.manifestFile).takeIf { it.isFile }?.readText() }
        val job = BundleJob(root, files.toList(), entryPoints, cdn.versions(manifest))
        val result = bundler.bundle(job).orElse { return error("the app couldn't be built: $it") }
        if (!result.succeeded) return failed(result)

        val written = withContext(Dispatchers.IO) { write(access, outPath, page, pageName, entryPoints, result) }.orElse { return error(it) }
        return ToolOutput(
            buildJsonObject {
                put(PAGE, "$outPath/$pageName")
                put(NAME, name)
                putJsonArray(FILES) { written.forEach { add(JsonPrimitive(it)) } }
                if (result.warnings.isNotEmpty()) putJsonArray(WARNINGS) { result.warnings.forEach { add(JsonPrimitive(it)) } }
            }.toString(),
            isError = false,
            provenance = BUILD_MESSAGES,
        )
    }

    /** The page to build from, and its file name in the output folder. */
    private fun page(entry: String, entryFile: File, title: String?): Pair<WebPage, String>? {
        val name = entryFile.name.lowercase()
        if (config.pageExtensions.any { name.endsWith(it) }) return WebPage(entryFile.readText(), entry) to entryFile.name
        if (config.entryExtensions.none { name.endsWith(it) }) return null
        val html = pageTemplate.render(mapOf(TEMPLATE_TITLE to escape(title ?: entryFile.nameWithoutExtension), TEMPLATE_ENTRY to "/" + entry))
        return (html as? Outcome.Success)?.let { WebPage(it.value, PAGE_FROM_SCRIPT) to PAGE_FROM_SCRIPT }
    }

    /** Every file the build may read, skipping ignored folders and the output folder; null past the limit. */
    private fun sourceFiles(root: File, outFolder: File): Set<String>? {
        val files = linkedSetOf<String>()
        val walk = root.walkTopDown().onEnter { it == root || (it.name !in config.ignoredDirectories && it != outFolder) }
        for (file in walk) {
            if (!file.isFile) continue
            files += file.relativeTo(root).invariantSeparatorsPath
            if (files.size > config.maxSourceFiles) return null
        }
        return files
    }

    /** Each script bundles to its own name; two scripts called main get main and main-2. */
    private fun entryPoints(scripts: List<String>): List<EntryPoint> {
        val used = mutableSetOf<String>()
        return scripts.map { path ->
            val base = path.substringAfterLast('/').substringBeforeLast('.').ifEmpty { FALLBACK_NAME }
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { used.add(it) }
            EntryPoint(path, name)
        }
    }

    private fun write(
        access: WorkspaceAccess,
        outPath: String,
        page: WebPage,
        pageName: String,
        entryPoints: List<EntryPoint>,
        result: BundleResult,
    ): Outcome<List<String>> {
        val produced = result.files.map { it.path }.toSet()
        val bundles = entryPoints.associate { entry ->
            entry.path to WebPage.Bundle(script = entry.outName + SCRIPT_SUFFIX, stylesheet = (entry.outName + STYLE_SUFFIX).takeIf { it in produced })
        }
        val outputs = result.files.map { it.path to it.bytes } + (pageName to page.built(bundles, config.moduleOutput).toByteArray())
        return try {
            val written = outputs.map { (path, bytes) ->
                val target = access.file("$outPath/$path").orElse { return Outcome.Failure(HarnessError.Storage(it)) }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                "$outPath/$path"
            }
            Outcome.Success(written)
        } catch (exception: IOException) {
            Outcome.Failure(HarnessError.Storage("couldn't save the built app: ${exception.message}"))
        }
    }

    private fun failed(result: BundleResult): ToolOutput = ToolOutput(
        buildJsonObject {
            put(ERROR, "the build failed; fix these and build again")
            putJsonArray(ERRORS) { result.errors.forEach { add(JsonPrimitive(it)) } }
            if (result.warnings.isNotEmpty()) putJsonArray(WARNINGS) { result.warnings.forEach { add(JsonPrimitive(it)) } }
        }.toString(),
        isError = true,
        provenance = BUILD_MESSAGES,
    )

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private inline fun <T> Outcome<T>.orElse(onFailure: (String) -> Nothing): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> onFailure(error.message)
    }

    companion object {
        const val ENTRY = "entry"
        const val NAME = "name"
        const val PAGE = "page"
        const val OUT_DIR = "outDir"
        const val TITLE = "title"
        private const val FILES = "files"
        private const val WARNINGS = "warnings"
        private const val ERROR = "error"
        private const val ERRORS = "errors"
        private const val TEMPLATE_TITLE = "title"
        private const val TEMPLATE_ENTRY = "entry"
        private const val PAGE_FROM_SCRIPT = "index.html"
        private const val SCRIPT_SUFFIX = ".js"
        private const val STYLE_SUFFIX = ".css"
        private const val FALLBACK_NAME = "app"

        /** Build messages quote the project's code and what the CDN answered. */
        private val BUILD_MESSAGES = Provenance.Untrusted("build messages")

        val DEFINITION = ToolDefinition(
            id = ToolId("build_web_app"),
            description = "Bundles a React/TypeScript web app in this chat's workspace, like a bundler on a computer: compiles " +
                ".ts/.tsx/.jsx, follows relative imports and imported CSS, and downloads npm packages imported by name " +
                "(versions from package.json when it lists them). entry is the app's index.html (its local script tags are " +
                "bundled) or one script such as src/main.tsx. Writes the page and bundles to outDir; once it builds, the user " +
                "sees the app as a card named name that they can open, and an open app reloads. On errors, fix the files and " +
                "build again.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$ENTRY":{"type":"string","description":"index.html, or a script like src/main.tsx"},""" +
                    """"$NAME":{"type":"string","description":"Short name the user sees on the app's card, e.g. Tip calculator"},""" +
                    """"$OUT_DIR":{"type":"string","description":"Folder for the built app, e.g. dist"},""" +
                    """"$TITLE":{"type":"string","description":"Page title when entry is a script; defaults to name"}""" +
                    """},"required":["$ENTRY","$NAME"]}""",
            ).jsonObject,
            // Only package names and CDN addresses leave the phone; the project's code never does.
            riskTier = RiskTier.WEB_READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_build_web_app",
            outputProvenance = BUILD_MESSAGES,
        )
    }
}
