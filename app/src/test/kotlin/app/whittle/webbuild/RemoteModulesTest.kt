package app.whittle.webbuild

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteModulesTest {
    private val server = MockWebServer().apply { start() }
    private val cacheDirectory = Files.createTempDirectory("whittle-modules").toFile()
    private val client = OkHttpClient.Builder().cache(Cache(cacheDirectory, 1_000_000)).build()
    private val modules = RemoteModules(client, maxBytes = 64, allows = { it.startsWith(server.url("/ok/").toString()) })

    @AfterTest
    fun cleanUp() {
        server.shutdown()
        cacheDirectory.deleteRecursively()
    }

    @Test
    fun `a module downloads once and is served from the cache when offline`() {
        server.enqueue(MockResponse().setBody("export default 1").setHeader("Content-Type", "application/javascript").setHeader("Cache-Control", "max-age=0"))
        val url = server.url("/ok/react.mjs").toString()

        val first = modules.fetch(url)
        assertEquals(200 to "application/javascript", first.status to first.mimeType)
        assertEquals("export default 1", first.body.reader().readText())

        server.shutdown()
        val offline = modules.fetch(url)
        assertEquals(200, offline.status)
        assertEquals("export default 1", offline.body.reader().readText())
    }

    @Test
    fun `missing, oversized and redirected-away modules are reported`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(404, modules.fetch(server.url("/ok/missing").toString()).status)

        server.enqueue(MockResponse().setBody("x".repeat(65)))
        assertEquals(403, modules.fetch(server.url("/ok/big.js").toString()).status)

        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere/x.js")))
        server.enqueue(MockResponse().setBody("redirected"))
        val redirected = modules.fetch(server.url("/ok/moved.js").toString())
        assertEquals(403, redirected.status)
        assertTrue("redirected" in redirected.body.reader().readText())
    }
}
