package app.whittle.tools.workspace

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolOutput
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildWebAppToolTest {
    private val root = Files.createTempDirectory("whittle-web-build").toFile()
    private val workspace = File(root, "chat-1")
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"), WorkspaceId("chat-1"))
    private val jobs = mutableListOf<BundleJob>()
    private var answer: (BundleJob) -> Outcome<BundleResult> = { job ->
        Outcome.Success(
            BundleResult(
                succeeded = true,
                files = job.entryPoints.flatMap { listOf(BundledFile("${it.outName}.js", "js".toByteArray()), BundledFile("${it.outName}.css", "css".toByteArray())) },
                errors = emptyList(),
                warnings = listOf("src/App.tsx:1:1: unused"),
            ),
        )
    }
    private val tool = BuildWebAppTool(
        Workspaces(root),
        { job -> jobs += job; answer(job) },
        CONFIG,
        PromptTemplate(ConfigName("page.html"), """<title>{{title}}</title><div id="root"></div><script type="module" src="{{entry}}"></script>"""),
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a page's scripts are bundled and the page is rewritten into the output folder`() = runBlocking<Unit> {
        write("index.html", """<html><head></head><body><script type="module" src="/src/main.tsx"></script></body></html>""")
        write("src/main.tsx", "import './App'")
        write("src/App.tsx", "export {}")
        write("package.json", """{"dependencies":{"react":"^19.0.0"}}""")
        write("node_modules/react/index.js", "ignored")
        write("dist/old.js", "previous build")

        val output = run("""{"entry":"index.html"}""")

        assertFalse(output.isError, output.content)
        assertEquals("dist/index.html", output.json()["page"]!!.jsonPrimitive.content)
        val job = jobs.single()
        assertEquals(listOf(EntryPoint("src/main.tsx", "main")), job.entryPoints)
        assertEquals(setOf("index.html", "src/main.tsx", "src/App.tsx", "package.json"), job.files.toSet(), "skips node_modules and the output folder")
        assertEquals(mapOf("react" to "^19.0.0"), job.packageVersions)
        val page = File(workspace, "dist/index.html").readText()
        assertTrue("""src="./main.js"""" in page && """href="./main.css"""" in page, page)
        assertEquals("js", File(workspace, "dist/main.js").readText())
        assertEquals("src/App.tsx:1:1: unused", output.json()["warnings"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `a script entry gets a page from the template`() = runBlocking<Unit> {
        write("src/main.tsx", "export {}")

        val output = run("""{"entry":"src/main.tsx","outDir":"build","title":"Tips & <Tricks>"}""")

        assertFalse(output.isError, output.content)
        val page = File(workspace, "build/index.html").readText()
        assertTrue("<title>Tips &amp; &lt;Tricks&gt;</title>" in page, page)
        assertTrue("""src="./main.js"""" in page, page)
    }

    @Test
    fun `project errors come back so the agent can fix them`() = runBlocking<Unit> {
        write("src/main.tsx", "const = ;")
        answer = { Outcome.Success(BundleResult(false, emptyList(), listOf("src/main.tsx:1:6: Unexpected \"=\""), emptyList())) }

        val output = run("""{"entry":"src/main.tsx"}""")

        assertTrue(output.isError)
        assertEquals("src/main.tsx:1:6: Unexpected \"=\"", output.json()["errors"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse(File(workspace, "dist").exists())
    }

    @Test
    fun `a bundler that can't run is an error`() = runBlocking<Unit> {
        write("src/main.tsx", "export {}")
        answer = { Outcome.Failure(HarnessError.Configuration("no WebView")) }
        assertTrue(run("""{"entry":"src/main.tsx"}""").isError)
    }

    @Test
    fun `bad entries and output folders are refused before building`() = runBlocking<Unit> {
        write("index.html", "<p>no scripts</p>")
        write("notes.txt", "hi")
        write("dist/index.html", """<script src="../src/main.tsx"></script>""")
        listOf(
            """{"entry":"missing.tsx"}""",
            """{"entry":"notes.txt"}""",
            """{"entry":"index.html"}""",
            """{"entry":"dist/index.html"}""",
            """{"entry":"index.html","outDir":"."}""",
            """{"entry":"index.html","outDir":"../elsewhere"}""",
        ).forEach { arguments -> assertTrue(run(arguments).isError, arguments) }
        assertTrue(jobs.isEmpty())
    }

    private fun write(path: String, text: String) {
        File(workspace, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private suspend fun run(arguments: String): ToolOutput = tool.execute(Json.parseToJsonElement(arguments).jsonObject, context)

    private fun ToolOutput.json(): JsonObject = Json.parseToJsonElement(content).jsonObject

    private companion object {
        val CONFIG = WebBuildConfig(
            schemaVersion = WebBuildConfig.SCHEMA_VERSION,
            defaultOutDir = "dist",
            entryExtensions = listOf(".tsx", ".ts", ".jsx", ".js"),
            pageExtensions = listOf(".html"),
            resolveExtensions = listOf(".tsx", ".ts"),
            loaders = mapOf(".tsx" to "tsx"),
            esbuildOptions = JsonObject(emptyMap()),
            moduleOutput = true,
            packages = PackageCdnConfig("https://cdn.example", "", "package.json", listOf("dependencies"), listOf("cdn.example"), 1_000),
            ignoredDirectories = listOf("node_modules"),
            maxSourceFiles = 100,
            maxMessages = 10,
            timeoutSeconds = 10,
            host = WebBuildHostConfig("build.example", "web-build", "index.html", "modules", 1_000, 60),
        )
    }
}
