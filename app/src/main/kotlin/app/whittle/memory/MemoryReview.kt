package app.whittle.memory

import app.whittle.agent.MemoryStore
import app.whittle.agent.OneShot
import app.whittle.chat.ThreadItem
import app.whittle.core.common.Outcome
import app.whittle.core.config.PromptTemplate
import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
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
 * The model a review calls: the cheaper one [models] names for [chosen]'s provider (by provider id), else [chosen]
 * itself. None when [chosen] can't be [reached] with an API key, as with Claude Code, which runs on the user's plan.
 */
fun reviewModel(chosen: ModelDescriptor?, models: Map<String, String>, reached: (ProviderId) -> Boolean): ModelDescriptor? {
    if (chosen == null || !reached(chosen.providerId)) return null
    val cheaper = models[chosen.providerId.value]?.takeIf { it != chosen.id.value } ?: return chosen
    // A short JSON answer needs no reasoning, and a small model may refuse the setting.
    return chosen.copy(id = ModelId(cheaper), displayName = cheaper, contextWindowTokens = null, capabilities = chosen.capabilities.copy(reasoning = false), pricing = null)
}

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
     * Reviews what the user said in [items] since the last review, unless the setting is off, there's no model to call
     * (see [reviewModel]), the chat is an [automation] (its words are Whittle's own prompt) or nothing new was said. A
     * failed call leaves the chat unreviewed so a later review tries again.
     */
    suspend fun review(chatId: String, items: List<ThreadItem>, chosen: ModelDescriptor?, automation: Boolean) {
        if (!settings() || automation) return
        val model = reviewModel(chosen, config.models, oneShot::reaches) ?: return
        val words = userWords(items)
        val reviewed = suggestions.reviewedCount(chatId)
        if (words.size <= reviewed) return
        val remembered = memory.current()
        val system = (prompt.render(mapOf("maxSuggestions" to config.maxSuggestions.toString())) as? Outcome.Success)?.value ?: return
        val seen = suggestions.seen()
        val input = input(remembered.map { "${it.id}: ${it.text}" }, seen, before = words.take(reviewed), new = words.drop(reviewed)) ?: return
        val reply = oneShot.complete(model, system, input, config.maxOutputTokens)
        val text = (reply as? Outcome.Success)?.value ?: return
        val known = remembered.map { it.id }.toSet()
        val facts = parse(text).filter { it.text.isNotBlank() }.take(config.maxSuggestions)
            .map { it.text to it.replaces?.takeIf(known::contains) }
        if (facts.isNotEmpty() && suggestions.add(chatId, facts) is Outcome.Failure) return
        suggestions.markReviewed(chatId, words.size)
    }

    /**
     * The remembered lines, what was already suggested or declined, the end of what the user said [before] (reviewed
     * already, cut to [MemoryReviewDefaults.contextChars]), then their [new] words with the newest kept: the oldest end
     * is cut to fit [MemoryReviewDefaults.maxInputChars]. Words reviewed before are never sent whole again.
     */
    private fun input(remembered: List<String>, seen: SeenSuggestions, before: List<String>, new: List<String>): String? {
        val variables = mapOf(
            "remembered" to remembered.joinToString("\n"),
            "waiting" to seen.waiting.joinToString("\n"),
            "declined" to seen.declined.joinToString("\n"),
            "context" to before.joinToString("\n").takeLast(config.contextChars),
            "words" to new.joinToString("\n").takeLast(config.maxInputChars),
        )
        return (inputPrompt.render(variables) as? Outcome.Success)?.value?.trimEnd()
    }

    /** The reply from its first `{` to its last `}`, read once, so prose around the JSON is fine; bad JSON is no facts. */
    private fun parse(text: String): List<Proposal> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        return runCatching { json.decodeFromString<Proposals>(text.substring(start, end + 1)) }.getOrNull()?.facts.orEmpty()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
