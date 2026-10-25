package app.whittle.memory

import app.whittle.agent.Memory
import app.whittle.agent.MemoryStore
import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.common.map
import app.whittle.settings.MemoryReviewDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A fact the review proposes, waiting for the user; [replaces] is the id of a memory it would rewrite. */
@Serializable
data class MemorySuggestion(val id: String, val text: String, val replaces: String?, val chatId: String, val atMillis: Long)

/**
 * What's kept between launches: the waiting suggestions, the texts the user dismissed (trimmed, lower case) so they
 * never come back, and how many of the user's words each chat had when it was last reviewed.
 */
@Serializable
data class SuggestionsState(val pending: List<MemorySuggestion>, val dismissed: List<String>, val reviewedUserWords: Map<String, Int>)

/** What the user has already seen of the review's proposals, as the model should hear it: still waiting, and declined. */
data class SeenSuggestions(val waiting: List<String>, val declined: List<String>)

/** Where [SuggestionsState] is kept; the phone uses an encrypted file. */
interface SuggestionsBackend {
    /** What was saved, or an empty state when nothing is or it can't be read. */
    suspend fun load(): SuggestionsState

    suspend fun save(state: SuggestionsState): Outcome<Unit>
}

/**
 * Facts the memory review proposed, which become memories only when the user keeps them. Texts that are already
 * remembered, already waiting or dismissed before are never proposed again; [seen] lets the review tell the model, so
 * it doesn't propose them in other words either. Dismissed texts and reviewed chats are capped by [limits], oldest
 * dropped first.
 */
class MemorySuggestions(
    private val backend: SuggestionsBackend,
    private val memory: MemoryStore,
    private val limits: MemoryReviewDefaults,
    private val clock: EpochClock,
    private val ids: IdGenerator,
    scope: CoroutineScope,
) {
    private val changes = Mutex()
    private val state = MutableStateFlow(SuggestionsState(emptyList(), emptyList(), emptyMap()))
    private val _pending = MutableStateFlow<List<MemorySuggestion>>(emptyList())
    private val loaded = scope.async {
        backend.load().also {
            state.value = it
            _pending.value = it.pending
        }
    }

    /** Waiting for the user, oldest first; empty until loaded. */
    val pending: StateFlow<List<MemorySuggestion>> = _pending.asStateFlow()

    /**
     * Adds each (text, id of the memory it would replace) the user hasn't seen. Texts longer than a memory may be are
     * skipped: keeping them would fail anyway.
     */
    suspend fun add(chatId: String, facts: List<Pair<String, String?>>): Outcome<Unit> = changes.withLock {
        val current = load()
        val known = (memory.current().map { it.text } + current.pending.map { it.text }).map(::normal).toMutableSet()
        known += current.dismissed
        val added = mutableListOf<MemorySuggestion>()
        for ((raw, replaces) in facts) {
            val text = raw.trim().replace(WHITESPACE, " ")
            if (text.isEmpty() || text.length > memory.policy.maxItemChars || !known.add(normal(text))) continue
            added += MemorySuggestion(ids.newId(), text, replaces, chatId, clock.nowMillis())
        }
        if (added.isEmpty()) Outcome.Success(Unit) else save(current.copy(pending = current.pending + added))
    }

    /**
     * Saves [text] (the user may have edited it) as a memory, rewriting the one this suggestion replaces, or saving
     * a new one when that's gone, and drops the suggestion. When memory refuses (full, too long) the suggestion stays
     * and the failure says why. When the memory is saved but the suggestion's removal can't be, that failure is
     * returned and the suggestion stays; keeping it again is harmless, since remember collapses duplicates.
     */
    suspend fun keep(id: String, text: String): Outcome<Memory> = changes.withLock {
        val current = load()
        val suggestion = current.pending.firstOrNull { it.id == id }
            ?: return@withLock Outcome.Failure(HarnessError.NotFound("That suggestion is gone"))
        val replaced = suggestion.replaces?.let { memory.change(it, text) }
        val changed = (replaced as? Outcome.Success)?.value
        val saved: Outcome<Memory> = when {
            replaced is Outcome.Failure -> replaced
            changed != null -> Outcome.Success(changed)
            else -> memory.remember(text)
        }
        if (saved !is Outcome.Success) return@withLock saved
        // Edited words were the user's answer to the original wording: it must not come back either.
        val dismissed = if (normal(text) == normal(suggestion.text)) current.dismissed else withDismissed(current.dismissed, suggestion.text)
        save(current.copy(pending = current.pending.filterNot { it.id == id }, dismissed = dismissed)).map { saved.value }
    }

    /** Drops the suggestion and remembers its words so it's never proposed again. */
    suspend fun dismiss(id: String): Outcome<Unit> = changes.withLock {
        val current = load()
        val suggestion = current.pending.firstOrNull { it.id == id } ?: return@withLock Outcome.Success(Unit)
        save(current.copy(pending = current.pending.filterNot { it.id == id }, dismissed = withDismissed(current.dismissed, suggestion.text)))
    }

    /** What the review should tell the model: the texts waiting, and the newest [MemoryReviewDefaults.maxDeclinedInInput] dismissed. */
    suspend fun seen(): SeenSuggestions {
        val current = load()
        return SeenSuggestions(current.pending.map { it.text }, current.dismissed.takeLast(limits.maxDeclinedInInput))
    }

    /** The chat was deleted: its waiting suggestions and its review count go. */
    suspend fun forgetChat(chatId: String): Outcome<Unit> = changes.withLock {
        val current = load()
        if (current.pending.none { it.chatId == chatId } && chatId !in current.reviewedUserWords) return@withLock Outcome.Success(Unit)
        save(current.copy(pending = current.pending.filterNot { it.chatId == chatId }, reviewedUserWords = current.reviewedUserWords - chatId))
    }

    /** All chats were deleted: nothing waits and no chat counts as reviewed. What was dismissed stays dismissed. */
    suspend fun forgetAllChats(): Outcome<Unit> = changes.withLock {
        save(load().copy(pending = emptyList(), reviewedUserWords = emptyMap()))
    }

    /** How many of the user's words [chatId] had when it was last reviewed; 0 if never. */
    suspend fun reviewedCount(chatId: String): Int = load().reviewedUserWords[chatId] ?: 0

    suspend fun markReviewed(chatId: String, userWords: Int): Outcome<Unit> = changes.withLock {
        val current = load()
        val reviewed = (current.reviewedUserWords - chatId + (chatId to userWords)).entries.toList()
            .takeLast(limits.maxReviewedChats).associate { it.key to it.value }
        save(current.copy(reviewedUserWords = reviewed))
    }

    private suspend fun load(): SuggestionsState {
        loaded.await()
        return state.value
    }

    /** Publishes [next] only once it's saved, as MemoryStore does, so what the UI shows is what a restart will find. */
    private suspend fun save(next: SuggestionsState): Outcome<Unit> = backend.save(next).map {
        state.value = next
        _pending.value = next.pending
    }

    private fun normal(text: String) = text.trim().lowercase()

    private fun withDismissed(dismissed: List<String>, text: String) = (dismissed - normal(text) + normal(text)).takeLast(limits.maxDismissed)

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}
