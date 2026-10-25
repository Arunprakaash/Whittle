package app.whittle.llm.anthropic

import app.whittle.llm.api.ApiFormat
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.MessageParam
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps a Claude turn exactly as the API returned it, as the turn's reasoning state, and sends it back unchanged. Thinking
 * blocks are only valid in the conversation that produced them, byte for byte, so the turn is replayed rather than rebuilt
 * from Whittle's text and tool calls. The state is tagged with its API format, so another provider never receives it.
 */
internal object AnthropicTurnCodec {
    private const val FORMAT_KEY = "format"
    private const val CONTENT_KEY = "content"

    fun wrap(content: JsonArray): JsonElement = JsonObject(
        mapOf(FORMAT_KEY to JsonPrimitive(ApiFormat.ANTHROPIC_MESSAGES.name), CONTENT_KEY to content),
    )

    fun unwrap(state: JsonElement?): JsonArray? {
        val wrapper = state as? JsonObject ?: return null
        if (wrapper[FORMAT_KEY]?.jsonPrimitive?.contentOrNull != ApiFormat.ANTHROPIC_MESSAGES.name) return null
        return wrapper[CONTENT_KEY] as? JsonArray
    }

    /** The assistant message that sends [content] back as it came. */
    fun toParam(content: JsonArray): MessageParam =
        jsonMapper().readValue("""{"role":"assistant","content":$content}""", MessageParam::class.java)

    /**
     * What may be sent back of a turn's [content]. When a server-side fallback took over mid-turn, a `fallback` block marks
     * where; before the last one only text and completed server-tool pairs may be echoed, since the declined model's
     * thinking and tool calls belong to it. The marker itself is dropped.
     */
    fun echoable(content: JsonArray): JsonArray {
        val boundary = content.indexOfLast { it.type == FALLBACK }
        if (boundary < 0) return content
        val before = content.take(boundary)
        val answered = before.mapNotNull { (it as? JsonObject)?.get(TOOL_USE_ID)?.jsonPrimitive?.contentOrNull }.toSet()
        val kept = before.filter { block ->
            when (block.type) {
                TEXT -> true
                SERVER_TOOL_USE -> (block as? JsonObject)?.get(ID)?.jsonPrimitive?.contentOrNull in answered
                else -> block.type?.endsWith(TOOL_RESULT_SUFFIX) == true && block.type != TOOL_RESULT
            }
        }
        return JsonArray(kept + content.drop(boundary + 1))
    }

    /** [content] without its tool calls, and without server tool calls that have no result: what is left of a cut-off turn. */
    fun withoutUnfinishedCalls(content: JsonArray): JsonArray {
        val answered = content.mapNotNull { (it as? JsonObject)?.get(TOOL_USE_ID)?.jsonPrimitive?.contentOrNull }.toSet()
        return JsonArray(
            content.filter { block ->
                when (block.type) {
                    TOOL_USE -> false
                    SERVER_TOOL_USE -> (block as? JsonObject)?.get(ID)?.jsonPrimitive?.contentOrNull in answered
                    else -> true
                }
            },
        )
    }

    /** The block types the stream parser reads by name. */
    const val FALLBACK = "fallback"
    const val TEXT = "text"
    const val SERVER_TOOL_USE = "server_tool_use"
    private const val TOOL_USE = "tool_use"
    private const val TOOL_RESULT = "tool_result"
    private const val TOOL_RESULT_SUFFIX = "_tool_result"
    private const val ID = "id"
    private const val TOOL_USE_ID = "tool_use_id"

    private val JsonElement.type: String? get() = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
}
