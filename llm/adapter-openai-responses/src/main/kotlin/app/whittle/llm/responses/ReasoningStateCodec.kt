package app.whittle.llm.responses

import app.whittle.llm.api.ApiFormat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wraps a Responses reasoning item so it is only ever sent back to the same API format. A conversation may
 * switch models mid-way, and another provider's reasoning state would be rejected.
 */
internal object ReasoningStateCodec {
    private const val FORMAT_KEY = "format"
    private const val ITEM_KEY = "item"
    private val RESENT_FIELDS = setOf(Field.TYPE, Field.ID, Field.ENCRYPTED_CONTENT, Field.SUMMARY)

    fun wrap(reasoningItem: JsonObject): JsonElement = JsonObject(
        mapOf(
            FORMAT_KEY to JsonPrimitive(ApiFormat.OPENAI_RESPONSES.name),
            ITEM_KEY to JsonObject(reasoningItem.filterKeys { it in RESENT_FIELDS }),
        ),
    )

    fun unwrap(state: JsonElement?): JsonObject? {
        val wrapper = state as? JsonObject ?: return null
        if (wrapper[FORMAT_KEY]?.jsonPrimitive?.contentOrNull != ApiFormat.OPENAI_RESPONSES.name) return null
        return wrapper[ITEM_KEY] as? JsonObject
    }
}
