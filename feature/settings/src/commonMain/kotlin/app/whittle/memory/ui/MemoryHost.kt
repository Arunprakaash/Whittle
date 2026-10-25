package app.whittle.memory.ui

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What Whittle remembers about the user, from wherever it's shown: the phone's own, or the same over the link. */
interface MemoryHost {
    val state: StateFlow<MemoryState>

    fun act(action: MemoryAction)

    /**
     * Saves [text] as a new memory, or in place of the one with [id]; with [suggestion], keeps that suggestion in these
     * words. Null once saved, otherwise why not.
     */
    suspend fun save(id: String?, text: String, suggestion: String? = null): MemoryIssue?
}

@Serializable
data class MemoryState(
    val memories: List<MemoryEntry> = emptyList(),
    /** How many it keeps, and how long each may be. */
    val maxItems: Int = 0,
    val maxItemChars: Int = 0,
    /** Kinds of thing to start with, while it's empty. */
    val ideas: List<MemoryIdeaEntry> = emptyList(),
    /** Facts the review proposed, waiting to be kept or dismissed, oldest first. */
    val suggested: List<SuggestedEntry> = emptyList(),
    /** Whether a chat is reviewed for facts once it goes quiet; null until the saved settings are read. */
    val suggesting: Boolean? = null,
)

@Serializable
data class MemoryEntry(val id: String, val text: String, val savedAtMillis: Long)

/** A suggested fact; [replaces] when keeping it rewrites something already remembered. */
@Serializable
data class SuggestedEntry(val id: String, val text: String, val replaces: Boolean)

/** A kind of thing to remember, with an example; [start] is what a new memory begins with. */
@Serializable
data class MemoryIdeaEntry(val icon: String, val title: String, val example: String, val start: String)

/**
 * Why a memory wasn't saved: there's no room for another, or the phone couldn't (a computer also when it didn't answer);
 * keeping a suggestion also when it's gone, or its words are too long.
 */
@Serializable
enum class MemoryIssue { Full, Failed, Gone, TooLong }

@Serializable
sealed interface MemoryAction {
    @Serializable
    @SerialName("forget")
    data class Forget(val id: String) : MemoryAction

    /** Drops a suggestion; it's never proposed again. */
    @Serializable
    @SerialName("dismiss")
    data class Dismiss(val id: String) : MemoryAction

    /** Turns reviewing quiet chats for facts on or off. */
    @Serializable
    @SerialName("suggest")
    data class Suggest(val on: Boolean) : MemoryAction

    /** Forgets everything. */
    @Serializable
    @SerialName("clear")
    data object Clear : MemoryAction
}
