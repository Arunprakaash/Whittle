package app.whittle.llm.responses

import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.Role
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.api.ReasoningFieldWriter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Converts the canonical conversation into a stateless Responses request: `store` is false, so the whole
 * conversation, including encrypted reasoning, is sent each time and nothing is retained by the provider.
 */
internal class ResponsesRequestMapper {
    fun map(request: ModelRequest, provider: ProviderDescriptor): JsonObject {
        val body = buildJsonObject {
            put(Field.MODEL, request.model.id.value)
            put(Field.INSTRUCTIONS, request.systemPrompt)
            put(Field.INPUT, JsonArray(request.messages.flatMap(::items)))
            val hosted = request.hostedTools.intersect(provider.hostedToolsFor(request.model.id))
            if ((request.tools.isNotEmpty() || hosted.isNotEmpty()) && request.model.capabilities.toolCalling) {
                putJsonArray(Field.TOOLS) {
                    request.tools.forEach { tool ->
                        addJsonObject {
                            put(Field.TYPE, ItemType.FUNCTION)
                            put(Field.NAME, tool.name)
                            put(Field.DESCRIPTION, tool.description)
                            put(Field.PARAMETERS, tool.inputSchema)
                            put(Field.STRICT, false)
                        }
                    }
                    hosted.forEach { tool -> addJsonObject { put(Field.TYPE, HostedToolWire.requestType(tool)) } }
                }
            }
            put(Field.STREAM, true)
            put(Field.STORE, false)
            putJsonArray(Field.INCLUDE) { add(INCLUDE_ENCRYPTED_REASONING) }
            put(provider.maxOutputTokensParameter, request.maxOutputTokens)
        }
        val overrides = provider.requestFieldsFor(request.model.id).filterKeys { it !in body }
        return ReasoningFieldWriter.apply(JsonObject(body + overrides), provider, request)
    }

    private fun items(message: Message): List<JsonObject> = when (message.role) {
        Role.USER -> userItems(message)
        Role.ASSISTANT -> assistantItems(message)
    }

    /** Tool outputs first, then any text or images the user added. */
    private fun userItems(message: Message): List<JsonObject> {
        val outputs = message.content.filterIsInstance<ContentBlock.ToolResult>().map {
            buildJsonObject {
                put(Field.TYPE, ItemType.FUNCTION_CALL_OUTPUT)
                put(Field.CALL_ID, it.toolCallId.value)
                put(Field.OUTPUT, it.content)
            }
        }
        val parts = message.content.mapNotNull(::userPart)
        val userMessage = parts.takeIf { it.isNotEmpty() }?.let {
            buildJsonObject {
                put(Field.TYPE, ItemType.MESSAGE)
                put(Field.ROLE, WireRole.USER)
                put(Field.CONTENT, JsonArray(it))
            }
        }
        return outputs + listOfNotNull(userMessage)
    }

    private fun userPart(block: ContentBlock): JsonObject? = when (block) {
        is ContentBlock.Text -> buildJsonObject {
            put(Field.TYPE, ContentType.INPUT_TEXT)
            put(Field.TEXT, block.text)
        }
        is ContentBlock.Image -> buildJsonObject {
            put(Field.TYPE, ContentType.INPUT_IMAGE)
            put(Field.IMAGE_URL, "data:${block.mediaType};base64,${block.base64Data}")
        }
        // The runner turns an attached image file into an Image before the request reaches an adapter.
        is ContentBlock.ImageFile, is ContentBlock.ToolCall, is ContentBlock.ToolResult, is ContentBlock.Reasoning, is ContentBlock.HostedToolUse -> null
    }

    /**
     * Keeps the provider's order: reasoning, then text, then function calls. Hosted tool calls are left out:
     * with `store` false the provider accepts the conversation without them, and their findings are in the text.
     */
    private fun assistantItems(message: Message): List<JsonObject> = message.content.mapNotNull { block ->
        when (block) {
            is ContentBlock.Reasoning -> ReasoningStateCodec.unwrap(block.providerState)
            is ContentBlock.Text -> buildJsonObject {
                put(Field.TYPE, ItemType.MESSAGE)
                put(Field.ROLE, WireRole.ASSISTANT)
                put(Field.CONTENT, buildJsonArray {
                    addJsonObject {
                        put(Field.TYPE, ContentType.OUTPUT_TEXT)
                        put(Field.TEXT, block.text)
                    }
                })
            }
            is ContentBlock.ToolCall -> buildJsonObject {
                put(Field.TYPE, ItemType.FUNCTION_CALL)
                put(Field.CALL_ID, block.id.value)
                put(Field.NAME, block.toolName)
                put(Field.ARGUMENTS, block.argumentsJson)
            }
            is ContentBlock.Image, is ContentBlock.ImageFile, is ContentBlock.ToolResult, is ContentBlock.HostedToolUse -> null
        }
    }
}
