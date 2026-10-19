package com.mobileagent.tools.api

import com.mobileagent.core.common.HarnessError
import com.mobileagent.core.common.Outcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a model's raw tool arguments and checks them against the tool's JSON Schema: required
 * properties and top-level property types. Errors go back to the model so it can correct itself.
 */
class ToolArgumentParser(private val json: Json = Json) {
    fun parse(argumentsJson: String, schema: JsonObject): Outcome<JsonObject> {
        val element = try {
            json.parseToJsonElement(argumentsJson)
        } catch (exception: SerializationException) {
            return invalid("Arguments are not valid JSON: ${exception.message}")
        }
        val arguments = element as? JsonObject ?: return invalid("Arguments must be a JSON object")
        missingRequired(arguments, schema)?.let { return invalid("Missing required properties: $it") }
        typeMismatches(arguments, schema).takeIf { it.isNotEmpty() }?.let { return invalid(it.joinToString("; ")) }
        return Outcome.Success(arguments)
    }

    private fun missingRequired(arguments: JsonObject, schema: JsonObject): List<String>? {
        val required = schema[SchemaKey.REQUIRED]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return (required - arguments.keys).takeIf { it.isNotEmpty() }
    }

    private fun typeMismatches(arguments: JsonObject, schema: JsonObject): List<String> {
        val properties = schema[SchemaKey.PROPERTIES]?.jsonObject ?: return emptyList()
        return arguments.mapNotNull { (name, value) ->
            val expected = properties[name]?.jsonObject?.get(SchemaKey.TYPE)?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            if (matchesType(value, expected)) null else "'$name' must be $expected"
        }
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        SchemaType.STRING -> value is JsonPrimitive && value.isString
        SchemaType.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        SchemaType.INTEGER -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
        SchemaType.NUMBER -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        SchemaType.OBJECT -> value is JsonObject
        SchemaType.ARRAY -> value is JsonArray
        SchemaType.NULL -> value is JsonNull
        else -> true
    }

    private fun invalid(message: String): Outcome<JsonObject> = Outcome.Failure(HarnessError.Serialization(message))

    private object SchemaKey {
        const val REQUIRED = "required"
        const val PROPERTIES = "properties"
        const val TYPE = "type"
    }

    private object SchemaType {
        const val STRING = "string"
        const val BOOLEAN = "boolean"
        const val INTEGER = "integer"
        const val NUMBER = "number"
        const val OBJECT = "object"
        const val ARRAY = "array"
        const val NULL = "null"
    }
}
