package app.whittle.updates

import app.whittle.settings.UpdateDefaults
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckTest {
    private val server = MockWebServer()
    private val file = File(Files.createTempDirectory("updates").toFile(), "update-check.json")
    private var now = 0L

    @AfterTest
    fun stop() = server.shutdown()

    /** Sends GitHub's address to [server] instead, so the config keeps its https-only rule. */
    private val http = OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }
        .build()

    private fun updates() = UpdateCheck(
        http,
        UpdateDefaults(check = true, latestReleaseUrl = "https://api.github.com/repos/x/releases/latest", apkPattern = "^Whittle-.*\\.apk$", everyHours = 24, fileName = file.name),
        currentVersion = "0.20.0",
        file = file,
    ) { now }

    private fun latest(tag: String) = MockResponse().setBody("""{"tag_name":"$tag","html_url":"https://github.com/x/releases/tag/$tag","assets":[]}""")

    @Test
    fun `a newer release is announced once, then stays available without asking again`() = runTest {
        server.enqueue(latest("v0.21.0"))
        val updates = updates()
        assertEquals("0.21.0", updates.checkIfDue()?.version)
        assertEquals("0.21.0", updates.available.value?.version)
        now += 3_600_000L
        // Not due yet: GitHub isn't asked, and the same version isn't announced twice.
        assertNull(updates.checkIfDue())
        assertEquals(1, server.requestCount)
        assertEquals("0.21.0", updates.available.value?.version)
    }

    @Test
    fun `the same version is not announced again after a restart`() = runTest {
        server.enqueue(latest("v0.21.0"))
        server.enqueue(latest("v0.21.0"))
        assertEquals("0.21.0", updates().checkIfDue()?.version)
        now += 25 * 3_600_000L
        val restarted = updates()
        assertNull(restarted.checkIfDue())
        assertEquals(2, server.requestCount)
        assertEquals("0.21.0", restarted.available.value?.version)
    }

    @Test
    fun `nothing newer, or a failed request, announces nothing`() = runTest {
        server.enqueue(latest("v0.20.0"))
        server.enqueue(MockResponse().setResponseCode(403))
        val updates = updates()
        assertNull(updates.checkIfDue())
        assertNull(updates.available.value)
        assertNull(updates.checkIfDue(force = true))
        assertNull(updates.available.value)
    }
}
