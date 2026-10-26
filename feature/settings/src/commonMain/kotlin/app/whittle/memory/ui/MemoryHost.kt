package app.whittle.memory.ui

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What Whittle remembers about the user, from wherever it's shown: the phone's own, or the same over the link. */
interface MemoryHost {
    val state: StateFlow<MemoryState>

    fun act(action: MemoryAction)

    /** Saves [text] as a new memory, or in place of the one with [id]; null once saved, otherwise why not. */
    suspend fun save(id: String?, text: String): MemoryIssue?
}

@Serializable
data class MemoryState(
    val memories: List<MemoryEntry> = emptyList(),
    /** How many it keeps, and how long each may be. */
    val maxItems: Int = 0,
    val maxItemChars: Int = 0,
    /** Kinds of thing to start with, while it's empty. */
    val ideas: List<MemoryIdeaEntry> = emptyList(),
)

@Serializable
data class MemoryEntry(val id: String, val text: String, val savedAtMillis: Long)

/** A kind of thing to remember, with an example; [start] is what a new memory begins with. */
@Serializable
data class MemoryIdeaEntry(val icon: String, val title: String, val example: String, val start: String)

/** Why a memory wasn't saved: there's no room for another, or the phone couldn't (a computer also when it didn't answer). */
@Serializable
enum class MemoryIssue { Full, Failed }

@Serializable
sealed interface MemoryAction {
    @Serializable
    @SerialName("forget")
    data class Forget(val id: String) : MemoryAction

    /** Forgets everything. */
    @Serializable
    @SerialName("clear")
    data object Clear : MemoryAction
}
