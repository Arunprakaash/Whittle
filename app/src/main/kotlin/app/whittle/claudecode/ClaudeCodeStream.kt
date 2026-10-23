package app.whittle.claudecode

import app.whittle.core.model.TokenUsage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What Claude Code reports while it works, read from its stream-json output. */
sealed interface ClaudeCodeEvent {
    /** The session started; [sessionId] carries the conversation on in the chat's next task. */
    data class Started(val sessionId: String) : ClaudeCodeEvent

    /** Words of the answer as they stream in. */
    data class TextDelta(val text: String) : ClaudeCodeEvent

    /** It called one of its tools. */
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ClaudeCodeEvent

    data class ToolResult(val id: String, val content: String, val isError: Boolean) : ClaudeCodeEvent

    /** It asks whether a tool call may run; answered with [ClaudeCodeStream.allow] or [ClaudeCodeStream.deny]. */
    data class PermissionRequest(val requestId: String, val toolName: String, val input: JsonObject, val toolUseId: String?) : ClaudeCodeEvent

    /** Something it asks the host that Whittle doesn't answer; refused with [ClaudeCodeStream.unsupported]. */
    data class OtherRequest(val requestId: String) : ClaudeCodeEvent

    /** A model call failed; [kind] is Claude Code's error kind, such as `authentication_failed` or `rate_limit`. */
    data class ApiError(val kind: String) : ClaudeCodeEvent

    /**
     * Where the Claude plan's usage stands: [status] is `allowed`, `allowed_warning` or `rejected`; [window] is
     * Claude Code's name for the limit, such as `five_hour`, and [resetsAtSeconds] when it reopens.
     */
    data class RateLimit(val status: String, val window: String?, val resetsAtSeconds: Long?) : ClaudeCodeEvent

    /** A model call failed for a passing reason and is tried again. */
    data class Retrying(val attempt: Int, val delayMillis: Long) : ClaudeCodeEvent

    /** The task ended; [text] is the answer, or what went wrong when [isError]. */
    data class Finished(val isError: Boolean, val text: String, val sessionId: String?, val usage: TokenUsage) : ClaudeCodeEvent
}

/**
 * Claude Code's print-mode protocol: one JSON object per line each way. Lines that aren't JSON (a warning on stderr)
 * and messages from its subagents are left out; the thread shows the main conversation.
 */
object ClaudeCodeStream {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(line: String): List<ClaudeCodeEvent> {
        val message = try {
            json.parseToJsonElement(line.trim()) as? JsonObject
        } catch (exception: SerializationException) {
            null
        } ?: return emptyList()
        // Subagents' own steps carry the call that started them; the thread follows the main conversation.
        if (message.string(PARENT_TOOL_USE_ID) != null) return emptyList()
        return when (message.string(TYPE)) {
            SYSTEM -> system(message)
            STREAM_EVENT -> listOfNotNull(textDelta(message.obj(EVENT)))
            ASSISTANT -> listOfNotNull(message.string(ERROR)?.let(ClaudeCodeEvent::ApiError)) +
                message.obj(MESSAGE)?.array(CONTENT).orEmpty().mapNotNull(::toolUse)
            USER -> message.obj(MESSAGE)?.array(CONTENT).orEmpty().mapNotNull(::toolResult)
            CONTROL_REQUEST -> listOfNotNull(request(message))
            RATE_LIMIT_EVENT -> listOfNotNull(rateLimit(message.obj(RATE_LIMIT_INFO)))
            RESULT -> listOf(finished(message))
            else -> emptyList()
        }
    }

    /** A message from the user, as Claude Code reads it on its input. */
    fun userMessage(text: String): String = buildJsonObject {
        put(TYPE, USER)
        putJsonObject(MESSAGE) {
            put(ROLE, USER)
            putJsonArray(CONTENT) {
                addJsonObject {
                    put(TYPE, TEXT)
                    put(TEXT, text)
                }
            }
        }
        put(PARENT_TOOL_USE_ID, JsonNull)
    }.toString()

    /** Lets the call run as asked. */
    fun allow(requestId: String, input: JsonObject): String = response(requestId) {
        put(BEHAVIOR, ALLOW)
        put(UPDATED_INPUT, input)
    }

    /** Refuses the call; Claude Code tells the model [message]. */
    fun deny(requestId: String, message: String): String = response(requestId) {
        put(BEHAVIOR, DENY)
        put(MESSAGE, message)
    }

    fun unsupported(requestId: String): String = buildJsonObject {
        put(TYPE, CONTROL_RESPONSE)
        putJsonObject(RESPONSE) {
            put(SUBTYPE, ERROR)
            put(REQUEST_ID, requestId)
            put(ERROR, UNSUPPORTED)
        }
    }.toString()

    private fun response(requestId: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String = buildJsonObject {
        put(TYPE, CONTROL_RESPONSE)
        putJsonObject(RESPONSE) {
            put(SUBTYPE, SUCCESS)
            put(REQUEST_ID, requestId)
            putJsonObject(RESPONSE, body)
        }
    }.toString()

    private fun system(message: JsonObject): List<ClaudeCodeEvent> = when (message.string(SUBTYPE)) {
        INIT -> listOfNotNull(message.string(SESSION_ID)?.let(ClaudeCodeEvent::Started))
        API_RETRY -> listOf(ClaudeCodeEvent.Retrying(message.int(ATTEMPT) ?: 1, message.long(RETRY_DELAY_MS) ?: 0))
        else -> emptyList()
    }

    private fun textDelta(event: JsonObject?): ClaudeCodeEvent? {
        if (event?.string(TYPE) != CONTENT_BLOCK_DELTA) return null
        val delta = event.obj(DELTA) ?: return null
        if (delta.string(TYPE) != TEXT_DELTA) return null
        return delta.string(TEXT)?.takeIf { it.isNotEmpty() }?.let(ClaudeCodeEvent::TextDelta)
    }

    private fun toolUse(block: JsonElement): ClaudeCodeEvent? {
        val content = block as? JsonObject ?: return null
        if (content.string(TYPE) != TOOL_USE) return null
        val id = content.string(ID) ?: return null
        val name = content.string(NAME) ?: return null
        return ClaudeCodeEvent.ToolUse(id, name, content.obj(INPUT) ?: JsonObject(emptyMap()))
    }

    private fun toolResult(block: JsonElement): ClaudeCodeEvent? {
        val content = block as? JsonObject ?: return null
        if (content.string(TYPE) != TOOL_RESULT) return null
        val id = content.string(TOOL_USE_ID) ?: return null
        val text = when (val value = content[CONTENT]) {
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            is JsonArray -> value.mapNotNull { (it as? JsonObject)?.string(TEXT) }.joinToString("\n")
            else -> ""
        }
        return ClaudeCodeEvent.ToolResult(id, text, (content[IS_ERROR] as? JsonPrimitive)?.booleanOrNull == true)
    }

    private fun rateLimit(info: JsonObject?): ClaudeCodeEvent? {
        val status = info?.string(STATUS) ?: return null
        return ClaudeCodeEvent.RateLimit(status, info.string(RATE_LIMIT_TYPE), info.long(RESETS_AT))
    }

    private fun request(message: JsonObject): ClaudeCodeEvent? {
        val id = message.string(REQUEST_ID) ?: return null
        val request = message.obj(REQUEST) ?: return ClaudeCodeEvent.OtherRequest(id)
        if (request.string(SUBTYPE) != CAN_USE_TOOL) return ClaudeCodeEvent.OtherRequest(id)
        val tool = request.string(TOOL_NAME) ?: return ClaudeCodeEvent.OtherRequest(id)
        return ClaudeCodeEvent.PermissionRequest(id, tool, request.obj(INPUT) ?: JsonObject(emptyMap()), request.string(TOOL_USE_ID))
    }

    private fun finished(message: JsonObject): ClaudeCodeEvent.Finished {
        val usage = message.obj(USAGE)
        return ClaudeCodeEvent.Finished(
            isError = (message[IS_ERROR] as? JsonPrimitive)?.booleanOrNull == true,
            text = message.string(RESULT).orEmpty(),
            sessionId = message.string(SESSION_ID),
            usage = TokenUsage(
                inputTokens = usage?.long(INPUT_TOKENS) ?: 0,
                outputTokens = usage?.long(OUTPUT_TOKENS) ?: 0,
                cacheReadTokens = usage?.long(CACHE_READ_TOKENS) ?: 0,
                cacheWriteTokens = usage?.long(CACHE_WRITE_TOKENS) ?: 0,
            ),
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private const val TYPE = "type"
    private const val RATE_LIMIT_EVENT = "rate_limit_event"
    private const val RATE_LIMIT_INFO = "rate_limit_info"
    private const val STATUS = "status"
    private const val RATE_LIMIT_TYPE = "rateLimitType"
    private const val RESETS_AT = "resetsAt"
    private const val SUBTYPE = "subtype"
    private const val SYSTEM = "system"
    private const val INIT = "init"
    private const val API_RETRY = "api_retry"
    private const val ATTEMPT = "attempt"
    private const val RETRY_DELAY_MS = "retry_delay_ms"
    private const val SESSION_ID = "session_id"
    private const val STREAM_EVENT = "stream_event"
    private const val EVENT = "event"
    private const val CONTENT_BLOCK_DELTA = "content_block_delta"
    private const val DELTA = "delta"
    private const val TEXT_DELTA = "text_delta"
    private const val ASSISTANT = "assistant"
    private const val USER = "user"
    private const val ROLE = "role"
    private const val MESSAGE = "message"
    private const val CONTENT = "content"
    private const val TEXT = "text"
    private const val TOOL_USE = "tool_use"
    private const val TOOL_RESULT = "tool_result"
    private const val TOOL_USE_ID = "tool_use_id"
    private const val IS_ERROR = "is_error"
    private const val ID = "id"
    private const val NAME = "name"
    private const val INPUT = "input"
    private const val PARENT_TOOL_USE_ID = "parent_tool_use_id"
    private const val CONTROL_REQUEST = "control_request"
    private const val CONTROL_RESPONSE = "control_response"
    private const val REQUEST = "request"
    private const val RESPONSE = "response"
    private const val REQUEST_ID = "request_id"
    private const val CAN_USE_TOOL = "can_use_tool"
    private const val TOOL_NAME = "tool_name"
    private const val SUCCESS = "success"
    private const val ERROR = "error"
    private const val UNSUPPORTED = "Whittle doesn't answer this request"
    private const val BEHAVIOR = "behavior"
    private const val ALLOW = "allow"
    private const val DENY = "deny"
    private const val UPDATED_INPUT = "updatedInput"
    private const val RESULT = "result"
    private const val USAGE = "usage"
    private const val INPUT_TOKENS = "input_tokens"
    private const val OUTPUT_TOKENS = "output_tokens"
    private const val CACHE_READ_TOKENS = "cache_read_input_tokens"
    private const val CACHE_WRITE_TOKENS = "cache_creation_input_tokens"
}
