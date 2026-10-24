package app.whittle.llm.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Wire format of the OpenAI Chat Completions API, also spoken by OpenRouter, Groq, Together,
// DeepSeek, Mistral, Qwen, Ollama, LM Studio and self-hosted gateways such as LiteLLM.

internal object WireRole {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
}

internal object WireType {
    const val FUNCTION = "function"
    const val TEXT = "text"
    const val IMAGE_URL = "image_url"
}

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireMessage>,
    val tools: List<WireTool>? = null,
    val stream: Boolean,
    @SerialName("stream_options") val streamOptions: StreamOptions,
)

@Serializable
internal data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean,
)

@Serializable
internal data class WireMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
internal data class WireToolCall(
    val id: String,
    val type: String,
    val function: WireFunctionCall,
)

@Serializable
internal data class WireFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class WireTool(
    val type: String,
    val function: WireFunctionDefinition,
)

@Serializable
internal data class WireFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
internal data class ChatCompletionChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: WireUsage? = null,
    val error: WireError? = null,
)

@Serializable
internal data class ChunkChoice(
    val delta: ChunkDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class ChunkDelta(
    val content: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
internal data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val function: FunctionDelta? = null,
)

@Serializable
internal data class FunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
internal data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
)

@Serializable
internal data class PromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Long = 0,
)

@Serializable
internal data class WireError(
    val message: String? = null,
)

@Serializable
internal data class ModelListResponse(
    val data: List<WireModel>,
)

@Serializable
internal data class WireModel(
    val id: String,
    val name: String? = null,
    /** Anthropic's name for [name]. */
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("context_length") val contextLength: Long? = null,
    /** Anthropic's name for [contextLength]. */
    @SerialName("max_input_tokens") val maxInputTokens: Long? = null,
    @SerialName("supported_parameters") val supportedParameters: List<String>? = null,
    val architecture: WireArchitecture? = null,
    val pricing: WirePricing? = null,
)

@Serializable
internal data class WireArchitecture(
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
)

@Serializable
internal data class WirePricing(
    val prompt: String? = null,
    val completion: String? = null,
)
