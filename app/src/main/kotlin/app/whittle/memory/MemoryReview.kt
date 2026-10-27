package app.whittle.memory

import app.whittle.agent.MemoryStore
import app.whittle.agent.OneShot
import app.whittle.chat.ThreadItem
import app.whittle.core.common.Outcome
import app.whittle.core.config.PromptTemplate
import app.whittle.llm.api.ModelDescriptor
import app.whittle.settings.MemoryReviewDefaults
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the user themselves typed in a chat, in order. Never options picked on a question card (the model wrote them,
 * perhaps after reading a web page), text shared in from another app, narration, steps, tool output, pasted content,
 * screen text or what attachments hold: the agent's side of a chat can carry words a web page or a file planted, and
 * only the user's own words may teach it about them.
 */
fun userWords(items: List<ThreadItem>): List<String> =
    items.filterIsInstance<ThreadItem.User>().filterNot { it.shared || it.fromCard }.map { it.text }.filter(String::isNotBlank)

/**
 * The memory review: after a chat, one model call reads the user's own words and proposes lasting facts about them.
 * Proposals only wait in [MemorySuggestions]; nothing becomes a memory until the user keeps it.
 */
class MemoryReview(
    private val oneShot: OneShot,
    private val suggestions: MemorySuggestions,
    private val memory: MemoryStore,
    private val prompt: PromptTemplate,
    private val inputPrompt: PromptTemplate,
    private val config: MemoryReviewDefaults,
    private val settings: () -> Boolean,
) {
    @Serializable
    private class Proposal(val text: String = "", val replaces: String? = null)

    @Serializable
    private class Proposals(val facts: List<Proposal> = emptyList())

    /**
     * Reviews what [items] now holds of the user's words, unless the setting is off, there's no [model], the chat is an
     * [automation] (its words are Whittle's own prompt) or nothing new was said since the last review. A failed call
     * leaves the chat unreviewed so a later review tries again.
     */
    suspend fun review(chatId: String, items: List<ThreadItem>, model: ModelDescriptor?, automation: Boolean) {
        if (!settings() || model == null || automation) return
        val words = userWords(items)
        if (words.size <= suggestions.reviewedCount(chatId)) return
        val remembered = memory.current()
        val system = (prompt.render(mapOf("maxSuggestions" to config.maxSuggestions.toString())) as? Outcome.Success)?.value ?: return
        val seen = suggestions.seen()
        val input = input(remembered.map { "${it.id}: ${it.text}" }, seen, words) ?: return
        val reply = oneShot.complete(model, system, input, config.maxOutputTokens)
        val text = (reply as? Outcome.Success)?.value ?: return
        val known = remembered.map { it.id }.toSet()
        val facts = parse(text).filter { it.text.isNotBlank() }.take(config.maxSuggestions)
            .map { it.text to it.replaces?.takeIf(known::contains) }
        if (facts.isNotEmpty() && suggestions.add(chatId, facts) is Outcome.Failure) return
        suggestions.markReviewed(chatId, words.size)
    }

    /**
     * The remembered lines, what was already suggested or declined, then the user's words with the newest kept: the
     * oldest end is cut to fit [MemoryReviewDefaults.maxInputChars].
     */
    private fun input(remembered: List<String>, seen: SeenSuggestions, words: List<String>): String? {
        val variables = mapOf(
            "remembered" to remembered.joinToString("\n"),
            "waiting" to seen.waiting.joinToString("\n"),
            "declined" to seen.declined.joinToString("\n"),
            "words" to words.joinToString("\n").takeLast(config.maxInputChars),
        )
        return (inputPrompt.render(variables) as? Outcome.Success)?.value?.trimEnd()
    }

    /** The first `{...}` block that reads as proposals; anything else, such as prose around it or bad JSON, is no facts. */
    private fun parse(text: String): List<Proposal> {
        var start = text.indexOf('{')
        while (start >= 0) {
            var end = text.lastIndexOf('}')
            while (end > start) {
                runCatching { json.decodeFromString<Proposals>(text.substring(start, end + 1)) }.getOrNull()?.let { return it.facts }
                end = text.lastIndexOf('}', end - 1)
            }
            start = text.indexOf('{', start + 1)
        }
        return emptyList()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
