package app.whittle.agent

import app.whittle.core.config.ContextPolicy
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Conversation
import app.whittle.core.model.Message
import app.whittle.core.model.Provenance
import app.whittle.core.model.Role
import app.whittle.llm.api.ModelDescriptor

/**
 * Keeps a long task inside the model's context window. Decides when a conversation has outgrown it, where to
 * cut so recent steps stay word for word, what the summarizer reads of the older ones, and how the summary
 * replaces them. Sizes are estimated from characters, deliberately on the large side.
 */
class ContextCompaction(private val policy: ContextPolicy, private val maxOutputTokens: Int) {
    /** Past this many tokens the conversation is compacted, leaving room for the reply. */
    fun threshold(model: ModelDescriptor): Long = (window(model) * policy.compactAtFraction).toLong() - maxOutputTokens

    /** [extraChars] covers what's sent besides the conversation, such as the tool definitions. */
    fun needsCompaction(model: ModelDescriptor, conversation: Conversation, extraChars: Int): Boolean =
        estimate(conversation, extraChars) > threshold(model)

    fun estimate(conversation: Conversation, extraChars: Int = 0): Long =
        tokens(conversation.systemPrompt.length + extraChars) + conversation.messages.sumOf(::estimate)

    fun estimate(message: Message): Long = message.content.sumOf { block ->
        when (block) {
            is ContentBlock.Text -> tokens(block.text.length)
            is ContentBlock.Image -> policy.imageTokens
            is ContentBlock.ToolCall -> tokens(block.toolName.length + block.argumentsJson.length)
            is ContentBlock.ToolResult -> tokens(block.content.length)
            is ContentBlock.HostedToolUse -> 0L // A record only; never sent back to a model.
            is ContentBlock.Reasoning -> tokens(block.text.orEmpty().length + (block.providerState?.toString()?.length ?: 0))
        }
    }

    /**
     * Where the kept part starts: an assistant turn, so no tool result is separated from its call, as far back
     * as the recent-steps share of the window allows (at least the last turn). Null when nothing comes before
     * it to summarize.
     */
    fun cutIndex(model: ModelDescriptor, messages: List<Message>): Int? {
        val keepBudget = (window(model) * policy.keepRecentFraction).toLong()
        var kept = 0L
        var cut: Int? = null
        for (index in messages.indices.reversed()) {
            kept += estimate(messages[index])
            if (messages[index].role != Role.ASSISTANT || index == 0) continue
            if (cut == null || kept <= keepBudget) cut = index else break
        }
        return cut
    }

    /**
     * The older steps as plain text for the summarizer: who said what, which tools ran and what came back,
     * each part cut to a fixed length. Outside content is labelled as such. When even that is too long for the
     * window, the first part (the request, or an earlier summary) and the latest parts are kept.
     */
    fun transcript(model: ModelDescriptor, messages: List<Message>): String {
        val parts = messages.flatMap { message -> message.content.mapNotNull { part(message.role, it) } }
        val limit = (threshold(model) * policy.charsPerToken).toLong()
        if (parts.sumOf { it.length.toLong() } <= limit || parts.size <= 1) return parts.joinToString(SEPARATOR)
        val first = parts.first()
        val latest = ArrayDeque<String>()
        var length = first.length.toLong() + OMITTED.length
        for (part in parts.drop(1).asReversed()) {
            if (length + part.length > limit) break
            latest.addFirst(part)
            length += part.length
        }
        return (listOf(first, OMITTED) + latest).joinToString(SEPARATOR)
    }

    /**
     * The summary as the conversation's first message, followed by the steps kept as they were. The user's own
     * words for this task ([instructions]) that only the summarized part held are added back verbatim.
     */
    fun compacted(summary: String, older: List<Message>, kept: List<Message>, instructions: List<String>, messages: AgentMessages): List<Message> {
        val outside = older.any(Message::carriesUntrustedContent)
        val keptText = kept.filter { it.role == Role.USER }.joinToString("\n") { it.text }
        val summarizedAway = instructions.filter { it !in keptText }
        val body = messages.compactedSummary(summary) +
            if (summarizedAway.isEmpty()) "" else "\n\n" + messages.compactedInstructions(summarizedAway.joinToString("\n\n"))
        val text = ContentBlock.Text(
            text = body,
            provenance = if (outside) Provenance.Untrusted(messages.summarySource) else Provenance.Trusted,
            personalData = older.any(Message::carriesPersonalData),
        )
        return listOf(Message(Role.USER, listOf(text))) + kept
    }

    private fun window(model: ModelDescriptor): Long = model.contextWindowTokens ?: policy.fallbackContextTokens

    private fun tokens(chars: Int): Long = kotlin.math.ceil(chars / policy.charsPerToken).toLong()

    private fun part(role: Role, block: ContentBlock): String? = when (block) {
        is ContentBlock.Text -> (if (role == Role.USER) USER else ASSISTANT) + clip(block.text)
        is ContentBlock.Image -> (if (role == Role.USER) USER else ASSISTANT) + IMAGE
        is ContentBlock.ToolCall -> "$ASSISTANT[called ${block.toolName}] ${clip(block.argumentsJson)}"
        is ContentBlock.ToolResult -> {
            val source = (block.provenance as? Provenance.Untrusted)?.let { " from ${it.sourceLabel}, outside content" }.orEmpty()
            val error = if (block.isError) " (error)" else ""
            "[result$error$source] ${clip(block.content)}"
        }
        is ContentBlock.HostedToolUse -> "$ASSISTANT[used ${block.toolName}: ${block.action}] ${clip(block.argumentsJson)}"
        is ContentBlock.Reasoning -> null
    }

    private fun clip(text: String): String =
        if (text.length <= policy.transcriptBlockChars) text else text.take(policy.transcriptBlockChars) + CLIPPED

    private companion object {
        const val USER = "User: "
        const val ASSISTANT = "Assistant: "
        const val IMAGE = "[image]"
        const val CLIPPED = " […]"
        const val OMITTED = "[… earlier steps left out …]"
        const val SEPARATOR = "\n\n"
    }
}
