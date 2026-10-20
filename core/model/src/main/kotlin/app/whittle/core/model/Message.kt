package app.whittle.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
data class Message(
    val role: Role,
    val content: List<ContentBlock>,
) {
    val toolCalls: List<ContentBlock.ToolCall>
        get() = content.filterIsInstance<ContentBlock.ToolCall>()

    val text: String
        get() = content.filterIsInstance<ContentBlock.Text>().joinToString(separator = "") { it.text }

    companion object {
        fun userText(text: String): Message = Message(Role.USER, listOf(ContentBlock.Text(text)))
    }
}

/** The full state the model sees: a system prompt plus alternating user and assistant messages. */
@Serializable
data class Conversation(
    val systemPrompt: String,
    val messages: List<Message>,
) {
    fun append(message: Message): Conversation = copy(messages = messages + message)
}
