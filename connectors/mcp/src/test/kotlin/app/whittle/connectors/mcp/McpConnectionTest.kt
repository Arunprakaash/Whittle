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
                    "/token" -> json("""{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}""")
                    else -> mcp(body)
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
