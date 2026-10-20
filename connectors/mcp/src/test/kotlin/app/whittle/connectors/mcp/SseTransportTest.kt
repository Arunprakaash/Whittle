package app.whittle.connectors.mcp

import app.whittle.core.common.EpochClock
import app.whittle.core.common.Outcome
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolExecutionContext
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The older HTTP+SSE transport against a small server that answers on its event stream, as such servers do. */
class SseTransportTest {
    private lateinit var server: HttpServer
    private val outbox = LinkedBlockingQueue<String>()
    private val seenKeys = mutableListOf<String?>()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sse") { exchange ->
            seenKeys += exchange.requestHeaders.getFirst("X-Api-Key")
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out: OutputStream = exchange.responseBody
            out.write("event: endpoint\ndata: /messages?session=1\n\n".toByteArray())
            out.flush()
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val message = outbox.take()
                        out.write("event: message\ndata: $message\n\n".toByteArray())
                        out.flush()
                    }
                }
            }
        }
        server.createContext("/messages") { exchange ->
            seenKeys += exchange.requestHeaders.getFirst("X-Api-Key")
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
            val id = body["id"] ?: return@createContext
            val result = when (body["method"]?.jsonPrimitive?.content) {
                "initialize" -> """{"protocolVersion":"2024-11-05","capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[{"name":"echo","description":"Echoes","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> """{"content":[{"type":"text","text":${JsonPrimitive("echo: " + (body["params"] as JsonObject)["arguments"])}}]}"""
                else -> "{}"
            }
            outbox.put("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    // Real time: the transport waits on a live stream, which virtual test time would skip past.
    fun `lists and calls tools over an sse stream with the user's header`() = runBlocking {
        val config = McpServerConfig(
            id = "old",
            name = "Old server",
            icon = "extension",
            url = "http://127.0.0.1:${server.address.port}/sse",
            redirectUri = "app.whittle://oauth/old",
            scopes = emptyList(),
            toolPrefix = "old_",
            sourceLabel = "Old server",
            defaultRiskTier = RiskTier.NETWORK_OUT,
            auth = McpAuthMode.TOKEN,
            transport = McpTransportKind.SSE,
        )
        val store = object : McpCredentialStore {
            var saved: McpCredentials? = null
            override suspend fun load() = saved
            override suspend fun save(credentials: McpCredentials) { saved = credentials }
            override suspend fun clear() { saved = null }
        }
        val connection = McpConnection(
            server = config,
            config = ConnectorsConfig(
                schemaVersion = ConnectorsConfig.SCHEMA_VERSION,
                protocolVersion = "2025-06-18",
                clientName = "Whittle",
                verifierEntropyBytes = 32,
                stateEntropyBytes = 16,
                refreshBeforeExpiryMillis = 60_000,
                maxResultChars = 1_000,
                servers = emptyList(),
                custom = CustomConnectorDefaults("mcp_", 16, "extension", "app.whittle://oauth/", RiskTier.NETWORK_OUT, 64),
                library = LibraryConfig("https://registry.example/v0/servers", 10, emptyList()),
            ),
            http = OkHttpClient(),
            clock = EpochClock { 0L },
            credentials = store,
            toolCache = Files.createTempDirectory("mcp-sse").resolve("tools.json").toFile(),
            clientVersion = "test",
        )

        val connected = connection.connectWithHeaders(mapOf("X-Api-Key" to "key-1"))
        assertIs<Outcome.Success<Unit>>(connected, (connected as? Outcome.Failure)?.error?.message)
        val tool = connection.tools.single()
        assertEquals("old_echo", tool.definition.id.value)
        val output = tool.execute(Json.parseToJsonElement("""{"say":"hi"}""").jsonObject, ToolExecutionContext(RunId("run"), ToolCallId("call")))
        assertEquals("""echo: {"say":"hi"}""", output.content)
        assertTrue(seenKeys.isNotEmpty() && seenKeys.all { it == "key-1" }, "the user's header goes with the stream and every message")
    }
}
