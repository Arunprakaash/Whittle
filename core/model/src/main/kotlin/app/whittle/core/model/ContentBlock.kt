package app.whittle.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Provider-neutral message content. Adapters convert to and from each provider's wire format. */
@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    @Serializable
    @SerialName("image")
    data class Image(val mediaType: String, val base64Data: String) : ContentBlock

    /** A tool call proposed by the model. Arguments stay raw so malformed JSON can be reported back to it. */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val id: ToolCallId,
        val toolName: String,
        val argumentsJson: String,
    ) : ContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: ToolCallId,
        val content: String,
        val isError: Boolean,
        val provenance: Provenance,
    ) : ContentBlock

    /** Reasoning output plus any opaque state a provider requires to be sent back unchanged. */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val text: String?,
        val providerState: JsonElement?,
    ) : ContentBlock
}
