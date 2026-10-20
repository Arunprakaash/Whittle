package app.whittle.connectors.mcp

import app.whittle.core.common.HarnessError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** How a JSON-RPC message reached the server, and what came back. */
internal sealed interface Reply {
    data class Result(val value: JsonElement) : Reply
    data class Error(val code: Int, val message: String) : Reply
    data object Unauthorized : Reply
    data object SessionGone : Reply
    data class Failed(val error: HarnessError) : Reply
}

/** Carries JSON-RPC messages to one server. [send] returns the reply to [id], or an empty result for a notification. */
internal interface McpTransport {
    suspend fun send(body: JsonObject, id: JsonElement?, headers: Map<String, String>): Reply

    /** Forgets the session or stream, so the next message opens a new one. */
    fun reset()
}

internal object JsonRpc {
    const val ID = "id"
    const val RESULT = "result"
    const val ERROR = "error"
    private const val CODE = "code"
    private const val MESSAGE = "message"
    private const val DATA_FIELD = "data:"
    private val EVENT_BOUNDARY = Regex("\r?\n\r?\n")
    val json = Json { ignoreUnknownKeys = true }

    /** The answer to [id] among [messages], as a reply. */
    fun answer(messages: Sequence<String>, id: JsonElement, server: String): Reply {
        val answer = messages
            .mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .firstOrNull { it[ID] == id && (RESULT in it || ERROR in it) }
            ?: return Reply.Failed(HarnessError.Serialization("$server sent no answer"))
        return reply(answer, server)
    }

    fun reply(answer: JsonObject, server: String): Reply {
        answer[ERROR]?.jsonObject?.let { error ->
            val code = (error[CODE] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
            return Reply.Error(code, (error[MESSAGE] as? JsonPrimitive)?.contentOrNull ?: "$server reported an error")
        }
        return Reply.Result(answer.getValue(RESULT))
    }

    /** The data of each server-sent event in a whole stream; an event's data lines join with newlines. */
    fun eventData(stream: String): Sequence<String> = stream.split(EVENT_BOUNDARY).asSequence().mapNotNull { event ->
        event.lines().filter { it.startsWith(DATA_FIELD) }.joinToString("\n") { it.removePrefix(DATA_FIELD).removePrefix(" ") }.ifEmpty { null }
    }

    val JSON_TYPE = "application/json".toMediaType()
}

/**
 * Streamable HTTP (MCP 2025-03-26 and later): every message is POSTed to one URL and answered with JSON or a
 * short event stream. The session id the server hands out goes back with every later message.
 */
internal class StreamableHttpTransport(
    private val endpoint: HttpUrl,
    private val http: OkHttpClient,
    private val protocolVersion: String,
    private val server: String,
) : McpTransport {
    @Volatile private var sessionId: String? = null

    override suspend fun send(body: JsonObject, id: JsonElement?, headers: Map<String, String>): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header(ACCEPT, ACCEPT_TYPES)
            .header(PROTOCOL_HEADER, protocolVersion)
            .apply { sessionId?.let { header(SESSION_HEADER, it) } }
            .post(body.toString().toRequestBody(JsonRpc.JSON_TYPE))
            .build()
        try {
            http.newCall(request).execute().use { response -> read(response, id) }
        } catch (exception: IOException) {
            Reply.Failed(HarnessError.Network(exception.message ?: exception.javaClass.simpleName))
        }
    }

    override fun reset() {
        sessionId = null
    }

    private fun read(response: Response, id: JsonElement?): Reply {
        val known = sessionId
        response.header(SESSION_HEADER)?.let { sessionId = it }
        return when {
            response.code == HTTP_UNAUTHORIZED -> Reply.Unauthorized
            response.code == HTTP_NOT_FOUND && known != null -> Reply.SessionGone
            !response.isSuccessful -> Reply.Failed(HarnessError.Provider(response.code, "$server answered HTTP ${response.code}"))
            id == null -> Reply.Result(JsonObject(emptyMap()))
            else -> {
                val body = response.body?.string().orEmpty()
                val streamed = response.header(CONTENT_TYPE).orEmpty().startsWith(EVENT_STREAM)
                JsonRpc.answer(if (streamed) JsonRpc.eventData(body) else sequenceOf(body), id, server)
            }
        }
    }

    private companion object {
        const val ACCEPT = "Accept"
        const val ACCEPT_TYPES = "application/json, text/event-stream"
        const val CONTENT_TYPE = "Content-Type"
        const val EVENT_STREAM = "text/event-stream"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val PROTOCOL_HEADER = "MCP-Protocol-Version"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
    }
}

/**
 * The older HTTP+SSE transport (MCP 2024-11-05), still used by some servers: a long-lived event stream from
 * [streamUrl] names an endpoint; messages are POSTed there and their answers arrive on the stream.
 */
internal class SseTransport(
    private val streamUrl: HttpUrl,
    http: OkHttpClient,
    private val server: String,
    private val answerTimeoutMillis: Long,
) : McpTransport {
    private val posts = http
    private val streams = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Volatile private var stream: Stream? = null

    private class Stream(val call: Call) {
        val endpoint = CompletableDeferred<HttpUrl>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

        @Volatile var closed = false
    }

    override suspend fun send(body: JsonObject, id: JsonElement?, headers: Map<String, String>): Reply {
        val open = when (val opened = open(headers)) {
            is Opened.Ready -> opened.stream
            is Opened.Refused -> return opened.reply
        }
        val endpoint = open.endpoint.await()
        val waiter = id?.let { CompletableDeferred<JsonObject>().also { waiter -> open.pending[it.toString()] = waiter } }
        val request = Request.Builder().url(endpoint)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toString().toRequestBody(JsonRpc.JSON_TYPE))
            .build()
        val posted = withContext(Dispatchers.IO) {
            try {
                posts.newCall(request).execute().use { response ->
                    when {
                        response.code == HTTP_UNAUTHORIZED -> Reply.Unauthorized
                        response.code == HTTP_NOT_FOUND -> Reply.SessionGone
                        !response.isSuccessful -> Reply.Failed(HarnessError.Provider(response.code, "$server answered HTTP ${response.code}"))
                        else -> null
                    }
                }
            } catch (exception: IOException) {
                Reply.Failed(HarnessError.Network(exception.message ?: exception.javaClass.simpleName))
            }
        }
        if (posted != null || waiter == null) {
            id?.let { open.pending.remove(it.toString()) }
            return posted ?: Reply.Result(JsonObject(emptyMap()))
        }
        val answer = withTimeoutOrNull(answerTimeoutMillis) { runCatching { waiter.await() }.getOrNull() }
        open.pending.remove(id.toString())
        return when {
            answer != null -> JsonRpc.reply(answer, server)
            open.closed -> Reply.SessionGone
            else -> Reply.Failed(HarnessError.Network("$server didn't answer in time"))
        }
    }

    override fun reset() {
        stream?.call?.cancel()
        stream = null
    }

    private sealed interface Opened {
        data class Ready(val stream: Stream) : Opened
        data class Refused(val reply: Reply) : Opened
    }

    private suspend fun open(headers: Map<String, String>): Opened = lock.withLock {
        stream?.takeIf { !it.closed }?.let { return@withLock Opened.Ready(it) }
        val request = Request.Builder().url(streamUrl)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header(ACCEPT, EVENT_STREAM)
            .get()
            .build()
        val call = streams.newCall(request)
        val response = try {
            withContext(Dispatchers.IO) { call.execute() }
        } catch (exception: IOException) {
            return@withLock Opened.Refused(Reply.Failed(HarnessError.Network(exception.message ?: exception.javaClass.simpleName)))
        }
        if (!response.isSuccessful) {
            response.close()
            return@withLock Opened.Refused(
                if (response.code == HTTP_UNAUTHORIZED) Reply.Unauthorized else Reply.Failed(HarnessError.Provider(response.code, "$server answered HTTP ${response.code}")),
            )
        }
        val opened = Stream(call)
        scope.launch { readEvents(response, opened) }
        if (withTimeoutOrNull(answerTimeoutMillis) { opened.endpoint.await() } == null) {
            call.cancel()
            return@withLock Opened.Refused(Reply.Failed(HarnessError.Network("$server didn't say where to send messages")))
        }
        stream = opened
        Opened.Ready(opened)
    }

    private fun readEvents(response: Response, stream: Stream) {
        try {
            response.use {
                val source = it.body?.source() ?: return
                var event = DEFAULT_EVENT
                val data = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            dispatch(event, data.toString(), stream)
                            event = DEFAULT_EVENT
                            data.clear()
                        }
                        line.startsWith(EVENT_FIELD) -> event = line.removePrefix(EVENT_FIELD).trim()
                        line.startsWith(DATA_FIELD) -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix(DATA_FIELD).removePrefix(" "))
                        }
                    }
                }
            }
        } catch (exception: IOException) {
            // The stream ended; waiting messages are told below.
        } finally {
            stream.closed = true
            stream.pending.values.forEach { it.cancel() }
        }
    }

    private fun dispatch(event: String, data: String, stream: Stream) {
        if (data.isEmpty()) return
        when (event) {
            ENDPOINT_EVENT -> streamUrl.resolve(data)?.let { stream.endpoint.complete(it) }
            DEFAULT_EVENT -> {
                val message = runCatching { JsonRpc.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
                val id = message[JsonRpc.ID] ?: return
                if (JsonRpc.RESULT in message || JsonRpc.ERROR in message) stream.pending.remove(id.toString())?.complete(message)
            }
        }
    }

    private companion object {
        const val ACCEPT = "Accept"
        const val EVENT_STREAM = "text/event-stream"
        const val EVENT_FIELD = "event:"
        const val DATA_FIELD = "data:"
        const val DEFAULT_EVENT = "message"
        const val ENDPOINT_EVENT = "endpoint"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
    }
}

