package app.whittle.webbuild

import app.whittle.tools.workspace.BundleJob
import app.whittle.tools.workspace.EntryPoint
import app.whittle.tools.workspace.PackageCdn
import app.whittle.tools.workspace.PackageCdnConfig
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildRoutesTest {
    private val root = Files.createTempDirectory("whittle-routes").toFile()
    private val cdn = PackageCdn(PackageCdnConfig("https://cdn.example", "target=es2022", "package.json", listOf("dependencies"), listOf("cdn.example"), 1_000))
    private val routes = BuildRoutes(
        assets = { path -> if (path == "esbuild/esbuild.wasm") "wasm".byteInputStream() else null },
        cdn = cdn,
        remote = RemoteModules(OkHttpClient(), 1_000, cdn::allows),
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `the page's own files come from the assets with their types`() {
        val wasm = routes.respond("/app/esbuild/esbuild.wasm", null)
        assertEquals(200 to "application/wasm", wasm.status to wasm.mimeType)
        assertEquals(404, routes.respond("/app/missing.js", null).status)
        assertEquals(404, routes.respond("/app/../secret", null).status)
    }

    @Test
    fun `a build reads only the files its job lists, and nothing once it ends`() {
        File(root, "src").mkdirs()
        File(root, "src/my app.tsx").writeText("export {}")
        File(root, "secret.txt").writeText("no")
        val urls = routes.open("job-1", job())

        val source = routes.respond("${urls.source}src/my%20app.tsx", null)
        assertEquals(200, source.status)
        assertEquals("export {}", source.body.reader().readText())
        assertEquals(404, routes.respond("${urls.source}secret.txt", null).status)
        assertEquals(404, routes.respond("${urls.source}src/../secret.txt", null).status)

        routes.close("job-1")
        assertEquals(404, routes.respond("${urls.source}src/my%20app.tsx", null).status)
    }

    @Test
    fun `packages resolve with the job's versions`() {
        val urls = routes.open("job-2", job(versions = mapOf("react-dom" to "^19.0.0")))
        val (path, query) = urls.resolve.split('?')
        val resolved = routes.respond(path, query + "react-dom%2Fclient")
        assertEquals("https://cdn.example/react-dom@%5E19.0.0/client?target=es2022", resolved.body.reader().readText())
        assertEquals(404, routes.respond(path, query + "node%3Afs").status)
    }

    @Test
    fun `modules outside the allowed CDNs are refused before any download`() {
        val urls = routes.open("job-3", job())
        val (path, query) = urls.remote.split('?')
        assertEquals(403, routes.respond(path, query + "https%3A%2F%2Fevil.example%2Fx.js").status)
        assertEquals(403, routes.respond(path, query + "http%3A%2F%2Fcdn.example%2Fx.js").status)
    }

    private fun job(versions: Map<String, String> = emptyMap()) =
        BundleJob(root, listOf("src/my app.tsx"), listOf(EntryPoint("src/my app.tsx", "main")), versions)
}
