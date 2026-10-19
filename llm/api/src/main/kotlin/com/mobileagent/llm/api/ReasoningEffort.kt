package com.mobileagent.llm.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How hard the model should think. Users pick it per run; the default comes from model-defaults.json. */
@Serializable
enum class ReasoningEffort { NONE, LOW, MEDIUM, HIGH }

/**
 * Where and how a provider expects the reasoning effort, e.g. `reasoning.effort` = "high" (OpenAI Responses,
 * OpenRouter) or `reasoning_effort` = "high". Providers without this config never receive an effort.
 */
@Serializable
data class ReasoningWireConfig(
    /** JSON object path of the field, e.g. ["reasoning", "effort"]. */
    val effortFieldPath: List<String>,
    /** Wire value for each effort. A missing entry means the provider has no equivalent; the field is omitted. */
    val effortValues: Map<ReasoningEffort, String>,
) {
    init {
        require(effortFieldPath.isNotEmpty()) { "effortFieldPath must not be empty" }
    }
}

/** Writes the request's reasoning effort into a provider request body when the model and provider support it. */
object ReasoningFieldWriter {
    fun apply(body: JsonObject, provider: ProviderDescriptor, request: ModelRequest): JsonObject {
        val wire = provider.reasoning ?: return body
        if (!request.model.capabilities.reasoning) return body
        val value = wire.effortValues[request.reasoningEffort] ?: return body
        return body.withValueAt(wire.effortFieldPath, JsonPrimitive(value))
    }

    private fun JsonObject.withValueAt(path: List<String>, value: JsonElement): JsonObject {
        val key = path.first()
        if (path.size == 1) return JsonObject(this + (key to value))
        val child = this[key] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(this + (key to child.withValueAt(path.drop(1), value)))
    }
}
