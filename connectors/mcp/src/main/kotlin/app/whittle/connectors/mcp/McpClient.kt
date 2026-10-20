package app.whittle.connectors.mcp

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

/** What a server says about a tool. Only ever used to ask more often, never less. */
@Serializable
data class ToolAnnotations(val readOnlyHint: Boolean? = null, val destructiveHint: Boolean? = null)

/** A tool as the server describes it. */
@Serializable
data class RemoteTool(val name: String, val description: String = "", val inputSchema: JsonObject, val annotations: ToolAnnotations? = null)

/** What a tool call returned: its text, and whether the server reports it as an error. */
data class RemoteResult(val text: String, val isError: Boolean)

/** Hands out the user's access token (null for a server without sign-in); [refresh] asks for a new one after a refusal. */
fun interface AccessTokens {
    suspend fun get(refresh: Boolean): Outcome<String?>
}

/**
 * An MCP client: JSON-RPC over the server's transport (Streamable HTTP, or the older SSE). Opens a session on
 * first use, opens a new one when the server forgets it, and asks once for a fresh token when refused.
 */
class McpClient(
    server: McpServerConfig,
    private val config: ConnectorsConfig,
    http: OkHttpClient,
    private val tokens: AccessTokens,
    /** Whittle's version, reported to the server when a session opens. */
    private val clientVersion: String,
    /** Headers the user gave this server (an API key header, say), sent with every message. */
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
) {
    private val endpoint = server.url.toHttpUrl().newBuilder().apply { server.query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    private val name = server.name
    private val ids = AtomicLong()
    private val sessionLock = Mutex()
    private val transport: McpTransport = when (server.transport) {
        McpTransportKind.STREAMABLE_HTTP -> StreamableHttpTransport(endpoint, http, config.protocolVersion, name)
        McpTransportKind.SSE -> SseTransport(endpoint, http, name, http.readTimeoutMillis.toLong())
    }

    @Volatile private var initialized = false

    suspend fun listTools(): Outcome<List<RemoteTool>> {
        val tools = mutableListOf<RemoteTool>()
        var cursor: String? = null
        do {
            val params = buildJsonObject { cursor?.let { put(CURSOR, it) } }
            val page = when (val result = request(METHOD_LIST_TOOLS, params)) {
                is Outcome.Success -> result.value.jsonObject
                is Outcome.Failure -> return result
            }
            try {
                (page[TOOLS] as? JsonArray).orEmpty().mapTo(tools) { json.decodeFromJsonElement(RemoteTool.serializer(), it) }
            } catch (exception: SerializationException) {
                return Outcome.Failure(HarnessError.Serialization("$name listed unreadable tools: ${exception.message}"))
            }
            cursor = (page[NEXT_CURSOR] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null)
        return Outcome.Success(tools)
    }

    suspend fun callTool(tool: String, arguments: JsonObject): Outcome<RemoteResult> {
        val params = buildJsonObject {
            put(NAME, tool)
            put(ARGUMENTS, arguments)
        }
        return when (val result = request(METHOD_CALL_TOOL, params)) {
            is Outcome.Success -> Outcome.Success(toResult(result.value.jsonObject))
            is Outcome.Failure -> result
        }
    }

    /** Forgets the session, e.g. after the user signs out. */
    fun reset() {
        initialized = false
        transport.reset()
    }

    private fun toResult(result: JsonObject): RemoteResult {
        val parts = (result[CONTENT] as? JsonArray).orEmpty().mapNotNull { part ->
            val block = part as? JsonObject ?: return@mapNotNull null
            when ((block[TYPE] as? JsonPrimitive)?.contentOrNull) {
                TYPE_TEXT -> block[TEXT]?.jsonPrimitive?.contentOrNull
                else -> block.toString()
            }
        }
        val text = parts.joinToString(PART_SEPARATOR).ifEmpty { result[STRUCTURED_CONTENT]?.toString().orEmpty() }
        val isError = (result[IS_ERROR] as? JsonPrimitive)?.contentOrNull == true.toString()
        return RemoteResult(text.take(config.maxResultChars), isError)
    }

    private suspend fun request(method: String, params: JsonObject): Outcome<JsonElement> {
        var refreshed = false
        var reopened = false
        while (true) {
            val sent = when (val built = headersFor(refreshed)) {
                is Outcome.Success -> built.value
                is Outcome.Failure -> return built
            }
            (open(sent) as? Outcome.Failure)?.let { return it }
            val body = rpc(method, params)
            when (val reply = transport.send(body, body[ID], sent)) {
                is Reply.Result -> return Outcome.Success(reply.value)
                is Reply.Error -> return Outcome.Failure(HarnessError.Provider(reply.code, reply.message))
                Reply.Unauthorized -> if (refreshed) return signInAgain() else { refreshed = true; reset() }
                Reply.SessionGone -> if (reopened) return Outcome.Failure(HarnessError.Network("$name keeps closing the connection")) else { reopened = true; reset() }
                is Reply.Failed -> return Outcome.Failure(reply.error)
            }
        }
    }

    /** The user's own headers, then the sign-in token, which wins for Authorization. */
    private suspend fun headersFor(refresh: Boolean): Outcome<Map<String, String>> {
        val token = when (val issued = tokens.get(refresh)) {
            is Outcome.Success -> issued.value
            is Outcome.Failure -> return issued
        }
        return Outcome.Success(headers() + listOfNotNull(token?.let { AUTHORIZATION to "$BEARER $it" }))
    }

    private suspend fun open(headers: Map<String, String>): Outcome<Unit> = sessionLock.withLock {
        if (initialized) return@withLock Outcome.Success(Unit)
        val initialize = rpc(
            METHOD_INITIALIZE,
            buildJsonObject {
                put(PROTOCOL_VERSION, config.protocolVersion)
                put(CAPABILITIES, JsonObject(emptyMap()))
                put(CLIENT_INFO, buildJsonObject { put(NAME, config.clientName); put(VERSION, clientVersion) })
            },
        )
        when (val reply = transport.send(initialize, initialize[ID], headers)) {
            is Reply.Result -> Unit
            is Reply.Error -> return@withLock Outcome.Failure(HarnessError.Provider(reply.code, reply.message))
            Reply.Unauthorized -> return@withLock signInAgain()
            Reply.SessionGone -> return@withLock Outcome.Failure(HarnessError.Network("$name refused a new connection"))
            is Reply.Failed -> return@withLock Outcome.Failure(reply.error)
        }
        transport.send(notification(METHOD_INITIALIZED), id = null, headers)
        initialized = true
        Outcome.Success(Unit)
    }

    private fun rpc(method: String, params: JsonObject) = buildJsonObject {
        put(JSONRPC, JSONRPC_VERSION)
        put(ID, ids.incrementAndGet())
        put(METHOD, method)
        put(PARAMS, params)
    }

    private fun notification(method: String) = buildJsonObject {
        put(JSONRPC, JSONRPC_VERSION)
        put(METHOD, method)
    }

    private fun signInAgain(): Outcome.Failure = Outcome.Failure(HarnessError.Authentication("$name signed Whittle out; connect it again in Settings"))

    private companion object {
        const val JSONRPC = "jsonrpc"
        const val JSONRPC_VERSION = "2.0"
        const val ID = "id"
        const val METHOD = "method"
        const val PARAMS = "params"
        const val METHOD_INITIALIZE = "initialize"
        const val METHOD_INITIALIZED = "notifications/initialized"
        const val METHOD_LIST_TOOLS = "tools/list"
        const val METHOD_CALL_TOOL = "tools/call"
        const val PROTOCOL_VERSION = "protocolVersion"
        const val CAPABILITIES = "capabilities"
        const val CLIENT_INFO = "clientInfo"
        const val VERSION = "version"
        const val NAME = "name"
        const val ARGUMENTS = "arguments"
        const val TOOLS = "tools"
        const val CURSOR = "cursor"
        const val NEXT_CURSOR = "nextCursor"
        const val CONTENT = "content"
        const val STRUCTURED_CONTENT = "structuredContent"
        const val IS_ERROR = "isError"
        const val TYPE = "type"
        const val TYPE_TEXT = "text"
        const val TEXT = "text"
        const val PART_SEPARATOR = "\n\n"
        const val AUTHORIZATION = "Authorization"
        const val BEARER = "Bearer"
        val json = Json { ignoreUnknownKeys = true }
    }
}
