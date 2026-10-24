package app.whittle.connectors.mcp

import app.whittle.core.common.EpochClock
import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpConnectionTest {
    private val web = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private var now = 1_000L

    /** Sign-in details only at the spec's fallback addresses: the root and OpenID discovery. */
    private var fallbackLayout = false

    /** What the token endpoint answers a refresh with; 200 gives new tokens. */
    private var refreshStatus = 200

    /** The MCP server answers 503, as when it's down. */
    private var serverDown = false

    /** The MCP server answers 401 to requests with this access token, as to one it no longer takes. */
    @Volatile private var refusedToken: String? = null
    private val store = object : McpCredentialStore {
        var saved: McpCredentials? = null
        override suspend fun load() = saved
        override suspend fun save(credentials: McpCredentials) { saved = credentials }
        override suspend fun clear() { saved = null }
    }

    @BeforeTest
    fun start() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val body = request.body.readUtf8()
                val base = web.url("/").toString().trimEnd('/')
                if (fallbackLayout) {
                    return when (request.path) {
                        "/.well-known/oauth-protected-resource" -> json("""{"authorization_servers":["$base/auth"],"scopes_supported":["mcp","files"]}""")
                        "/auth/.well-known/openid-configuration" -> json(
                            """{"authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","registration_endpoint":"$base/register"}""",
                        )
                        "/register" -> json("""{"client_id":"client-1"}""")
                        else -> if (request.path!!.startsWith("/.well-known") || request.path!!.startsWith("/auth")) MockResponse().setResponseCode(404) else mcp(body)
                    }
                }
                return when (request.path) {
                    "/.well-known/oauth-protected-resource/mcp" -> json("""{"authorization_servers":["$base"]}""")
                    "/.well-known/oauth-authorization-server" -> json(
                        """{"authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","registration_endpoint":"$base/register"}""",
                    )
                    "/register" -> json("""{"client_id":"client-1","client_secret":"secret-1"}""")
                    "/token" -> if (body.contains("grant_type=refresh_token")) {
                        if (refreshStatus != 200) {
                            json("""{"error":"invalid_grant"}""").setResponseCode(refreshStatus)
                        } else {
                            json("""{"access_token":"access-refreshed","refresh_token":"refresh-refreshed","expires_in":3600}""")
                        }
                    } else {
                        // Each sign-in gets tokens of its own: code-2 gives access-2.
                        val n = Regex("code=code-(\\d+)").find(body)?.groupValues?.get(1) ?: "1"
                        json("""{"access_token":"access-$n","refresh_token":"refresh-$n","expires_in":3600}""")
                    }
                    else -> when {
                        serverDown -> MockResponse().setResponseCode(503)
                        refusedToken != null && request.getHeader("Authorization") == "Bearer $refusedToken" -> MockResponse().setResponseCode(401)
                        else -> mcp(body)
                    }
                }
            }
        }
        web.start()
    }

    @AfterTest
    fun stop() = web.shutdown()

    @Test
    fun `signs in, opens a session and calls the server's tools`() = runTest {
        val connection = connection()
        val signInPage = assertIs<Outcome.Success<String>>(connection.beginSignIn()).value.toHttpUrl()
        assertEquals("client-1", signInPage.queryParameter("client_id"))
        assertEquals("S256", signInPage.queryParameter("code_challenge_method"))
        assertEquals(server().url, signInPage.queryParameter("resource"))

        val state = signInPage.queryParameter("state")
        assertIs<Outcome.Success<Unit>>(connection.complete("${REDIRECT}?code=code-1&state=$state"))
        assertEquals(ConnectorState.Connected(3), connection.state.value)

        val tool = connection.tools.single { it.definition.id.value == "db_list_tables" }
        assertEquals(RiskTier.WEB_READ, tool.definition.riskTier)
        assertEquals(RiskTier.NETWORK_OUT, connection.tools.single { it.definition.id.value == "db_apply_migration" }.definition.riskTier)
        val output = tool.execute(buildJsonObject { put("schema", "public") }, ToolExecutionContext(RunId("run"), ToolCallId("call")))
        assertEquals("tables: habits", output.content)
        assertIs<Provenance.Untrusted>(output.provenance)

        val mcpCalls = requests.filter { it.path!!.startsWith("/mcp") }
        assertTrue(mcpCalls.all { it.getHeader("Authorization") == "Bearer access-1" })
        assertTrue(mcpCalls.drop(1).all { it.getHeader("Mcp-Session-Id") == SESSION }, "every call after initialize uses the session")
        assertTrue(mcpCalls.all { it.requestUrl!!.queryParameter("features") == "database" })
    }

    @Test
    fun `a client the user gave is used instead of registering`() = runTest {
        val connection = connection()
        connection.useClient(OAuthClientCredentials("my-client", "my-secret"))
        val signInPage = assertIs<Outcome.Success<String>>(connection.beginSignIn()).value.toHttpUrl()
        assertEquals("my-client", signInPage.queryParameter("client_id"))
        assertTrue(requests.none { it.path == "/register" })

        val state = signInPage.queryParameter("state")
        assertIs<Outcome.Success<Unit>>(connection.complete("${REDIRECT}?code=code-1&state=$state"))
        val token = requests.single { it.path == "/token" }
        assertEquals(okhttp3.Credentials.basic("my-client", "my-secret"), token.getHeader("Authorization"))
        assertEquals("my-client", store.saved?.preRegistered?.clientId, "kept for the next sign-in")
    }

    @Test
    fun `sign-in details are found at the spec's fallback addresses`() = runTest {
        fallbackLayout = true
        val connection = connection(server().copy(scopes = emptyList()))
        val signInPage = assertIs<Outcome.Success<String>>(connection.beginSignIn()).value.toHttpUrl()
        assertEquals("client-1", signInPage.queryParameter("client_id"))
        assertEquals("mcp files", signInPage.queryParameter("scope"), "the server's own scopes, when the connection names none")
    }

    @Test
    fun `a sign-in answered for another attempt is refused`() = runTest {
        val connection = connection()
        connection.beginSignIn()
        assertIs<Outcome.Failure>(connection.complete("${REDIRECT}?code=code-1&state=forged"))
        assertEquals(ConnectorState.Disconnected, connection.state.value)
    }

    @Test
    fun `an expiring token is refreshed before use`() = runTest {
        val connection = connection()
        val state = (connection.beginSignIn() as Outcome.Success).value.toHttpUrl().queryParameter("state")
        connection.complete("${REDIRECT}?code=code-1&state=$state")
        now += 3_600_000
        connection.refreshTools()
        assertEquals(2, requests.count { it.path == "/token" })
    }

    @Test
    fun `a refused refresh asks the user to sign in again, even after a restart`() = runTest {
        val connection = signedIn()
        refreshStatus = 400
        now += 3_600_000
        connection.refreshTools()
        assertEquals(ConnectorState.SignInExpired, connection.state.value)
        assertTrue(connection.tools.isEmpty())
        assertEquals(true, store.saved?.signedOut)

        val restarted = connection()
        restarted.restore()
        assertEquals(ConnectorState.SignInExpired, restarted.state.value)

        refreshStatus = 200
        val state = (restarted.beginSignIn() as Outcome.Success).value.toHttpUrl().queryParameter("state")
        assertIs<Outcome.Success<Unit>>(restarted.complete("${REDIRECT}?code=code-2&state=$state"))
        assertEquals(ConnectorState.Connected(3), restarted.state.value)
        assertEquals(false, store.saved?.signedOut)
    }

    @Test
    fun `a sign-in server that is down doesn't sign the user out`() = runTest {
        val connection = signedIn()
        refreshStatus = 503
        now += 3_600_000
        connection.refreshTools()
        assertIs<ConnectorState.Connected>(connection.state.value)
        assertEquals(false, store.saved?.signedOut)
    }

    @Test
    fun `a server that is down keeps its tools and says so until it answers again`() = runTest {
        val connection = signedIn()
        serverDown = true
        connection.refreshTools()
        assertEquals(ConnectorState.Connected(3, reachable = false), connection.state.value)
        assertEquals(3, connection.tools.size)
        serverDown = false
        connection.refreshTools()
        assertEquals(ConnectorState.Connected(3), connection.state.value)
    }

    @Test
    fun `disconnecting a signed-out connection stops asking`() = runTest {
        val connection = signedIn()
        refreshStatus = 400
        now += 3_600_000
        connection.refreshTools()
        connection.disconnect()
        assertEquals(ConnectorState.Disconnected, connection.state.value)
        assertEquals(false, store.saved?.signedOut)
    }

    @Test
    fun `a 401 when a session opens refreshes the token and keeps a sign-in that still works`() = runTest {
        signedIn()
        // The token isn't seen as expiring (no expires_in, or a clock behind), but the server no longer takes it.
        refusedToken = "access-1"
        val restarted = connection()
        restarted.restore()
        assertEquals(ConnectorState.Connected(3), restarted.state.value)
        assertEquals("access-refreshed", store.saved?.tokens?.accessToken)
        assertEquals(false, store.saved?.signedOut)
        // A session opens without a session id; the last one opened is the one in use.
        val opened = requests.last { it.path!!.startsWith("/mcp") && it.getHeader("Mcp-Session-Id") == null }
        assertEquals("Bearer access-refreshed", opened.getHeader("Authorization"))
    }

    @Test
    fun `a 401 when a session opens signs out only once the refresh is refused`() = runTest {
        signedIn()
        refusedToken = "access-1"
        refreshStatus = 400
        val restarted = connection()
        restarted.restore()
        assertEquals(ConnectorState.SignInExpired, restarted.state.value)
        assertEquals(true, store.saved?.signedOut)
    }

    @Test
    fun `a 401 when a session opens, with the sign-in server down, keeps the sign-in`() = runTest {
        signedIn()
        refusedToken = "access-1"
        refreshStatus = 503
        val restarted = connection()
        restarted.restore()
        assertIs<ConnectorState.Connected>(restarted.state.value)
        assertEquals(false, store.saved?.signedOut)
        assertEquals("access-1", store.saved?.tokens?.accessToken)
    }

    private suspend fun signedIn(): McpConnection {
        val connection = connection()
        val state = (connection.beginSignIn() as Outcome.Success).value.toHttpUrl().queryParameter("state")
        assertIs<Outcome.Success<Unit>>(connection.complete("${REDIRECT}?code=code-1&state=$state"))
        return connection
    }

    @Test
    fun `a pasted token is sent as a bearer token and kept`() = runTest {
        val connection = connection(server().copy(auth = McpAuthMode.TOKEN))
        assertIs<Outcome.Success<Unit>>(connection.connectWithToken(" token-1 "))
        assertEquals(ConnectorState.Connected(3), connection.state.value)
        assertTrue(requests.filter { it.path!!.startsWith("/mcp") }.all { it.getHeader("Authorization") == "Bearer token-1" })
        assertEquals("Bearer token-1", store.saved?.headers?.get("Authorization"))
    }

    @Test
    fun `a server without sign-in gets no authorization header`() = runTest {
        val connection = connection(server().copy(auth = McpAuthMode.NONE))
        assertIs<Outcome.Success<Unit>>(connection.connectOpen())
        assertTrue(requests.filter { it.path!!.startsWith("/mcp") }.all { it.getHeader("Authorization") == null })
    }

    @Test
    fun `a tool the server calls destructive always asks`() = runTest {
        val connection = connection(server().copy(auth = McpAuthMode.NONE))
        connection.connectOpen()
        assertEquals(RiskTier.DELETE_OR_PAY, connection.tools.single { it.definition.id.value == "db_drop_table" }.definition.riskTier)
    }

    @Test
    fun `the probe tells sign-in kinds apart`() = runTest {
        val probe = McpProbe(config(), OkHttpClient())
        assertEquals(McpAuthMode.NONE, assertIs<Outcome.Success<McpAuthMode>>(probe.detect(web.url("/mcp").toString())).value)
        assertIs<Outcome.Failure>(probe.detect("http://example.com/mcp"))
    }

    @Test
    fun `switched-off tools are hidden and headers the user gave are sent`() = runTest {
        val connection = connection(server().copy(auth = McpAuthMode.TOKEN))
        connection.connectWithHeaders(mapOf("X-Api-Key" to " key-1 "))
        assertTrue(requests.filter { it.path!!.startsWith("/mcp") }.all { it.getHeader("X-Api-Key") == "key-1" && it.getHeader("Authorization") == null })
        connection.exclude(setOf("apply_migration"))
        assertEquals(listOf("db_drop_table", "db_list_tables"), connection.tools.map { it.definition.id.value }.sorted())
        assertEquals(4, connection.offered.size)
    }

    private fun connection(server: McpServerConfig = server()) = McpConnection(
        server = server,
        config = config(),
        http = OkHttpClient(),
        clock = EpochClock { now },
        credentials = store,
        toolCache = Files.createTempDirectory("mcp").resolve("tools.json").toFile(),
        clientVersion = "test",
    )

    private fun config() = ConnectorsConfig(
            schemaVersion = ConnectorsConfig.SCHEMA_VERSION,
            protocolVersion = "2025-06-18",
            clientName = "Whittle",
            verifierEntropyBytes = 32,
            stateEntropyBytes = 16,
            refreshBeforeExpiryMillis = 60_000,
            maxResultChars = 1_000,
            servers = listOf(server()),
            custom = CustomConnectorDefaults("mcp_", 16, "extension", "app.whittle://oauth/", testLoopback, RiskTier.NETWORK_OUT, 64),
            library = LibraryConfig("https://registry.example/v0/servers", 10, categories = emptyList(), servers = emptyList()),
    )

    private fun server() = McpServerConfig(
        id = "db",
        name = "Database",
        icon = "database",
        url = web.url("/mcp").toString(),
        query = mapOf("features" to "database"),
        redirectUri = REDIRECT,
        scopes = listOf("database:read"),
        toolPrefix = "db_",
        sourceLabel = "Database",
        defaultRiskTier = RiskTier.NETWORK_OUT,
        riskTiers = mapOf("list_tables" to RiskTier.WEB_READ),
    )

    private fun mcp(body: String): MockResponse {
        val id = Regex("\"id\":(\\d+)").find(body)?.groupValues?.get(1)
        val method = Regex("\"method\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        return when (method) {
            "initialize" -> json(rpc(id, """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}""")).setHeader("Mcp-Session-Id", SESSION)
            "notifications/initialized" -> MockResponse().setResponseCode(202)
            "tools/list" -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: message\ndata: " + rpc(
                    id,
                    """{"tools":[{"name":"list_tables","description":"Lists tables","inputSchema":{"type":"object","properties":{"schema":{"type":"string"}}}},""" +
                        """{"name":"apply_migration","inputSchema":{"properties":{"query":{"type":"string"}}}},""" +
                        """{"name":"drop_table","inputSchema":{"type":"object"},"annotations":{"destructiveHint":true}},""" +
                        """{"name":"bad name!","inputSchema":{"type":"object"}}]}""",
                ) + "\n\n",
            )
            "tools/call" -> json(rpc(id, """{"content":[{"type":"text","text":"tables: habits"}],"isError":false}"""))
            else -> MockResponse().setResponseCode(400)
        }
    }

    private fun rpc(id: String?, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val REDIRECT = "app.whittle://oauth/db"
        const val SESSION = "session-1"
    }
}
