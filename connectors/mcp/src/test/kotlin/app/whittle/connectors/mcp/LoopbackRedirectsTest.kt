package app.whittle.connectors.mcp

import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal val testLoopback = LoopbackSignIn(
    timeoutMillis = 60_000,
    returnUri = "app.whittle://oauth/return",
    pageTitle = "Signed in",
    pageMessage = "Go back",
    pageButton = "Back",
    expiredTitle = "Nothing here",
    expiredMessage = "Expired",
)

class LoopbackRedirectsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val base = "http://127.0.0.1:$port/oauth/".toHttpUrl()
    private val page = PromptTemplate(ConfigName("page.html"), "<h1>{{title}}</h1><p>{{message}}</p><a href=\"{{link}}\">{{button}}</a>")
    private val http = OkHttpClient()
    private val returned = mutableListOf<String>()

    @AfterTest
    fun stop() = scope.cancel()

    private fun receiver(accepts: Boolean = true) = LoopbackRedirects(base, testLoopback, page, scope, System::currentTimeMillis) {
        returned += it
        accepts
    }

    private fun get(path: String): Pair<Int, String> =
        http.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute().use { it.code to it.body!!.string() }

    @Test
    fun `a sign-in coming back is handed over whole and answered with a way back to Whittle`() {
        val loopback = receiver()
        assertTrue(loopback.serves("http://127.0.0.1:$port/oauth/linear"))
        assertIs<Outcome.Success<Unit>>(loopback.listen())

        val (status, body) = get("/oauth/linear?code=abc&state=xyz")

        assertEquals(200, status)
        assertEquals(listOf("http://127.0.0.1:$port/oauth/linear?code=abc&state=xyz"), returned)
        assertEquals("<h1>Signed in</h1><p>Go back</p><a href=\"app.whittle://oauth/return\">Back</a>", body)
    }

    @Test
    fun `addresses nobody is waiting for get the expired page, and other paths aren't handed over`() {
        receiver(accepts = false).listen()

        assertTrue("Nothing here" in get("/oauth/old?code=abc&state=xyz").second)
        assertEquals(404, get("/favicon.ico").first)
        assertEquals(listOf("http://127.0.0.1:$port/oauth/old?code=abc&state=xyz"), returned)
    }
}
