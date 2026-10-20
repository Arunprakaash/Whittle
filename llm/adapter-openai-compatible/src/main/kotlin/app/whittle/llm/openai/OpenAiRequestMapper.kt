package app.whittle.llm.openai

import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.Role
import app.whittle.llm.api.ModelRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Converts the canonical conversation into a Chat Completions request. */
internal class OpenAiRequestMapper {
    fun map(request: ModelRequest): ChatCompletionRequest = ChatCompletionRequest(
        model = request.model.id.value,
        messages = buildList {
            add(WireMessage(role = WireRole.SYSTEM, content = JsonPrimitive(request.systemPrompt)))
            request.messages.forEach { addAll(mapMessage(it)) }
        },
        tools = request.tools
            .takeIf { it.isNotEmpty() && request.model.capabilities.toolCalling }
            ?.map { WireTool(WireType.FUNCTION, WireFunctionDefinition(it.name, it.description, it.inputSchema)) },
        stream = true,
        streamOptions = StreamOptions(includeUsage = true),
    )

    private fun mapMessage(message: Message): List<WireMessage> = when (message.role) {
        Role.ASSISTANT -> listOf(mapAssistant(message))
        Role.USER -> mapUser(message)
    }

    private fun mapAssistant(message: Message): WireMessage {
        val toolCalls = message.toolCalls.map {
            WireToolCall(it.id.value, WireType.FUNCTION, WireFunctionCall(it.toolName, it.argumentsJson))
        }
        return WireMessage(
            role = WireRole.ASSISTANT,
            content = message.text.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive),
            toolCalls = toolCalls.ifEmpty { null },
        )
    }

    /** Tool results become separate `tool` messages; everything else stays in one `user` message. */
    private fun mapUser(message: Message): List<WireMessage> {
        val toolResults = message.content.filterIsInstance<ContentBlock.ToolResult>().map {
            WireMessage(role = WireRole.TOOL, content = JsonPrimitive(it.content), toolCallId = it.toolCallId.value)
        }
        val userContent = message.content.filter { it is ContentBlock.Text || it is ContentBlock.Image }
        val userMessage = userContent.takeIf { it.isNotEmpty() }?.let {
            WireMessage(role = WireRole.USER, content = userContentJson(it))
        }
        return toolResults + listOfNotNull(userMessage)
    }

    private fun userContentJson(blocks: List<ContentBlock>): JsonElement {
        val texts = blocks.filterIsInstance<ContentBlock.Text>()
        if (texts.size == blocks.size) return JsonPrimitive(texts.joinToString(separator = "") { it.text })
        return buildJsonArray {
            blocks.forEach { block ->
                if (block is ContentBlock.Text) {
                    addJsonObject {
                        put("type", WireType.TEXT)
                        put("text", block.text)
                    }
                }
                if (block is ContentBlock.Image) {
                    addJsonObject {
                        put("type", WireType.IMAGE_URL)
                        putJsonObject(WireType.IMAGE_URL) { put("url", "data:${block.mediaType};base64,${block.base64Data}") }
                    }
                }
            }
        }
    }
}
