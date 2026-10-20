package app.whittle.connectors.mcp

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** A tool as the server describes it. */
@Serializable
data class RemoteTool(val name: String, val description: String = "", val inputSchema: JsonObject)

/** What a tool call returned: its text, and whether the server reports it as an error. */
data class RemoteResult(val text: String, val isError: Boolean)

/** Hands out the user's access token; [refresh] asks for a new one after the server refused the last. */
fun interface AccessTokens {
    suspend fun get(refresh: Boolean): Outcome<String>
}

/**
 * MCP over Streamable HTTP: JSON-RPC requests POSTed to one URL, answered with JSON or a server-sent
 * event stream. Opens a session on first use and opens a new one when the server forgets it.
 */
class McpClient(
    server: McpServerConfig,
    private val config: ConnectorsConfig,
    private val http: OkHttpClient,
    private val tokens: AccessTokens,
    /** Whittle's version, reported to the server when a session opens. */
    private val clientVersion: String,
) {
    private val endpoint = server.url.toHttpUrl().newBuilder().apply { server.query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    private val name = server.name
    private val ids = AtomicLong()
    private val sessionLock = Mutex()

    @Volatile private var session: Session? = null

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
        session = null
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
            val token = when (val issued = tokens.get(refreshed)) {
                is Outcome.Success -> issued.value
                is Outcome.Failure -> return issued
            }
            val current = when (val opened = openSession(token)) {
                is Outcome.Success -> opened.value
                is Outcome.Failure -> return opened
            }
            when (val reply = post(rpc(method, params), token, current.id)) {
                is Reply.Result -> return Outcome.Success(reply.value)
                is Reply.Error -> return Outcome.Failure(HarnessError.Provider(reply.code, reply.message))
                Reply.Unauthorized -> if (refreshed) return signInAgain() else { refreshed = true; session = null }
                Reply.SessionGone -> if (reopened) return Outcome.Failure(HarnessError.Network("$name keeps closing the connection")) else { reopened = true; session = null }
                is Reply.Failed -> return Outcome.Failure(reply.error)
            }
        }
    }

    private suspend fun openSession(token: String): Outcome<Session> = sessionLock.withLock {
        session?.let { return@withLock Outcome.Success(it) }
        val initialize = rpc(
            METHOD_INITIALIZE,
            buildJsonObject {
                put(PROTOCOL_VERSION, config.protocolVersion)
                put(CAPABILITIES, JsonObject(emptyMap()))
                put(CLIENT_INFO, buildJsonObject { put(NAME, config.clientName); put(VERSION, clientVersion) })
            },
        )
        val sessionId = when (val reply = post(initialize, token, sessionId = null)) {
            is Reply.Result -> reply.sessionId
            is Reply.Error -> return@withLock Outcome.Failure(HarnessError.Provider(reply.code, reply.message))
            Reply.Unauthorized -> return@withLock signInAgain()
            Reply.SessionGone -> return@withLock Outcome.Failure(HarnessError.Network("$name refused a new connection"))
            is Reply.Failed -> return@withLock Outcome.Failure(reply.error)
        }
        val opened = Session(sessionId)
        post(notification(METHOD_INITIALIZED), token, opened.id)
        session = opened
        Outcome.Success(opened)
    }

    private suspend fun post(body: JsonObject, token: String, sessionId: String?): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint)
            .header(AUTHORIZATION, "$BEARER $token")
            .header(ACCEPT, ACCEPT_TYPES)
            .header(PROTOCOL_HEADER, config.protocolVersion)
            .apply { sessionId?.let { header(SESSION_HEADER, it) } }
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        try {
            http.newCall(request).execute().use { response -> read(response, body[ID]) }
        } catch (exception: IOException) {
            Reply.Failed(HarnessError.Network(exception.message ?: exception.javaClass.simpleName))
        }
    }

    private fun read(response: Response, id: JsonElement?): Reply {
        val sessionId = response.header(SESSION_HEADER)
        when {
            response.code == HTTP_UNAUTHORIZED -> return Reply.Unauthorized
            response.code == HTTP_NOT_FOUND && session != null -> return Reply.SessionGone
            !response.isSuccessful -> return Reply.Failed(HarnessError.Provider(response.code, "$name answered HTTP ${response.code}"))
            id == null -> return Reply.Result(JsonObject(emptyMap()), sessionId)
        }
        val body = response.body?.string().orEmpty()
        val messages = if (response.header(CONTENT_TYPE).orEmpty().startsWith(EVENT_STREAM)) eventData(body) else listOf(body)
        val answer = messages.asSequence()
            .mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .firstOrNull { it[ID] == id && (RESULT in it || ERROR in it) }
            ?: return Reply.Failed(HarnessError.Serialization("$name sent no answer"))
        answer[ERROR]?.jsonObject?.let { error ->
            val code = (error[CODE] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
            return Reply.Error(code, (error[MESSAGE] as? JsonPrimitive)?.contentOrNull ?: "$name reported an error")
        }
        return Reply.Result(answer.getValue(RESULT), sessionId)
    }

    /** The data of each server-sent event; an event's data lines join with newlines. */
    private fun eventData(stream: String): List<String> = stream.split(EVENT_BOUNDARY).mapNotNull { event ->
        event.lines().filter { it.startsWith(DATA_FIELD) }.joinToString("\n") { it.removePrefix(DATA_FIELD).removePrefix(" ") }.ifEmpty { null }
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

    private class Session(val id: String?)

    private sealed interface Reply {
        data class Result(val value: JsonElement, val sessionId: String?) : Reply
        data class Error(val code: Int, val message: String) : Reply
        data object Unauthorized : Reply
        data object SessionGone : Reply
        data class Failed(val error: HarnessError) : Reply
    }

    private companion object {
        const val JSONRPC = "jsonrpc"
        const val JSONRPC_VERSION = "2.0"
        const val ID = "id"
        const val METHOD = "method"
        const val PARAMS = "params"
        const val RESULT = "result"
        const val ERROR = "error"
        const val CODE = "code"
        const val MESSAGE = "message"
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
        const val ACCEPT = "Accept"
        const val ACCEPT_TYPES = "application/json, text/event-stream"
        const val CONTENT_TYPE = "Content-Type"
        const val EVENT_STREAM = "text/event-stream"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val PROTOCOL_HEADER = "MCP-Protocol-Version"
        const val DATA_FIELD = "data:"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        val EVENT_BOUNDARY = Regex("\r?\n\r?\n")
        val JSON_TYPE = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }
}
