package app.whittle.link

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What changed between two JSON values, as JSON, so the link sends a screen's changes rather than the whole screen.
 * A patch is one of:
 * - `{"=": value}`: the value, whole;
 * - `{"+": "text"}`: text added to the end of a string, as a reply streaming in;
 * - `{"o": {key: patch}, "x": [key]}`: an object's changed keys, and the keys gone;
 * - `{"a": {"n": length, "s": {"index": patch}}}`: a list's new length, and its changed items.
 */
object JsonPatch {
    /** The patch from [old] to [new]; null when they're the same. */
    fun diff(old: JsonElement?, new: JsonElement): JsonElement? = when {
        old == new -> null
        old is JsonObject && new is JsonObject -> diffObjects(old, new)
        old is JsonArray && new is JsonArray -> diffArrays(old, new)
        appended(old, new) -> buildJsonObject { put(APPEND, JsonPrimitive(new.jsonPrimitive.content.substring(old!!.jsonPrimitive.content.length))) }
        else -> replace(new)
    }

    /** [base] with [patch] applied; a patch that doesn't fit its base (a key for a list, say) replaces nothing and fails. */
    fun apply(base: JsonElement?, patch: JsonElement): JsonElement {
        val parts = patch.jsonObject
        parts[REPLACE]?.let { return it }
        parts[APPEND]?.let { return JsonPrimitive(base!!.jsonPrimitive.content + it.jsonPrimitive.content) }
        parts[OBJECT]?.let { changed ->
            val result = base!!.jsonObject.toMutableMap()
            parts[REMOVED]?.jsonArray?.forEach { result.remove(it.jsonPrimitive.content) }
            changed.jsonObject.forEach { (key, child) -> result[key] = apply(result[key], child) }
            return JsonObject(result)
        }
        val list = parts.getValue(ARRAY).jsonObject
        val items = base!!.jsonArray
        val changed = list[SET]?.jsonObject.orEmpty()
        return JsonArray(List(list.getValue(LENGTH).jsonPrimitive.int) { index -> changed[index.toString()]?.let { apply(items.getOrNull(index), it) } ?: items[index] })
    }

    private fun diffObjects(old: JsonObject, new: JsonObject): JsonElement = buildJsonObject {
        put(OBJECT, buildJsonObject { new.forEach { (key, value) -> diff(old[key], value)?.let { put(key, it) } } })
        val removed = old.keys - new.keys
        if (removed.isNotEmpty()) put(REMOVED, buildJsonArray { removed.forEach { add(JsonPrimitive(it)) } })
    }

    private fun diffArrays(old: JsonArray, new: JsonArray): JsonElement = buildJsonObject {
        put(ARRAY, buildJsonObject {
            put(LENGTH, JsonPrimitive(new.size))
            put(SET, buildJsonObject { new.forEachIndexed { index, value -> diff(old.getOrNull(index), value)?.let { put(index.toString(), it) } } })
        })
    }

    /** [new] is [old], a string, with more on the end. */
    private fun appended(old: JsonElement?, new: JsonElement): Boolean =
        old is JsonPrimitive && new is JsonPrimitive && old.isString && new.isString && new.content.length > old.content.length && new.content.startsWith(old.content)

    private fun replace(value: JsonElement) = buildJsonObject { put(REPLACE, value) }

    private const val REPLACE = "="
    private const val APPEND = "+"
    private const val OBJECT = "o"
    private const val REMOVED = "x"
    private const val ARRAY = "a"
    private const val LENGTH = "n"
    private const val SET = "s"
}
