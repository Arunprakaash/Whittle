package app.whittle.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Provider-neutral message content. Adapters convert to and from each provider's wire format. */
@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        /** Untrusted when the text was made from outside content, such as a summary of earlier web reads. */
        val provenance: Provenance = Provenance.Trusted,
        /** The text holds the user's personal data, as when it summarizes contacts or events. */
        val personalData: Boolean = false,
    ) : ContentBlock

    @Serializable
    @SerialName("image")
    data class Image(val mediaType: String, val base64Data: String) : ContentBlock

    /**
     * An image the user attached, kept as a file in the chat's workspace at [path]. The runner reads it into an
     * [Image] just before each model call, so stored chats and checkpoints never hold its bytes. Untrusted when another
     * app controls what it shows, as with a screenshot of the screen the user called the assistant over.
     */
    @Serializable
    @SerialName("image_file")
    data class ImageFile(val path: String, val provenance: Provenance = Provenance.Trusted) : ContentBlock

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
        /** The output holds the user's personal data, such as contacts or calendar events. */
        val personalData: Boolean = false,
    ) : ContentBlock

    /**
     * A tool the provider ran on its own servers during the turn, such as a web search. Kept as a record
     * only: it is never sent back to a model, but it tells later turns that web content entered the chat.
     */
    @Serializable
    @SerialName("hosted_tool_use")
    data class HostedToolUse(
        val toolName: String,
        val action: String,
        val argumentsJson: String,
    ) : ContentBlock

    /** Reasoning output plus any opaque state a provider requires to be sent back unchanged. */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val text: String?,
        val providerState: JsonElement?,
    ) : ContentBlock
}
