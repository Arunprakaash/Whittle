package app.whittle.settings

import app.whittle.AppGraph
import app.whittle.core.common.Outcome
import app.whittle.memory.ui.MemoryAction
import app.whittle.memory.ui.MemoryEntry
import app.whittle.memory.ui.MemoryHost
import app.whittle.memory.ui.MemoryIdeaEntry
import app.whittle.memory.ui.MemoryIssue
import app.whittle.memory.ui.MemoryState
import app.whittle.memory.ui.SuggestedEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The phone's memory as the Memory page shows it, and its changes saved: for the phone and the user's computers alike. */
class PhoneMemoryHost(private val graph: AppGraph) : MemoryHost {
    private val policy = graph.memory.policy
    private val ideas = graph.memoryIdeas.map { MemoryIdeaEntry(icon = it.icon, title = it.title, example = it.example, start = it.start) }

    override val state: StateFlow<MemoryState> = combine(graph.memory.all, graph.memorySuggestions.pending, graph.currentSettings) { memories, suggested, settings ->
        MemoryState(
            memories = memories.map { MemoryEntry(id = it.id, text = it.text, savedAtMillis = it.savedAtMillis) },
            maxItems = policy.maxItems,
            maxItemChars = policy.maxItemChars,
            ideas = ideas,
            suggested = suggested.map { SuggestedEntry(id = it.id, text = it.text, replaces = it.replaces != null) },
            suggesting = settings?.suggestMemories,
        )
    }.stateIn(graph.appScope, SharingStarted.Eagerly, MemoryState(maxItems = policy.maxItems, maxItemChars = policy.maxItemChars, ideas = ideas))

    // Writes go to the app's scope so leaving the screen never drops a change.
    override fun act(action: MemoryAction) {
        graph.appScope.launch {
            when (action) {
                is MemoryAction.Forget -> graph.memory.forget(action.id)
                MemoryAction.Clear -> graph.memory.clear()
                // A dismiss that can't be saved leaves the suggestion on the page, so it shows it didn't go.
                is MemoryAction.Dismiss -> graph.memorySuggestions.dismiss(action.id)
                is MemoryAction.Suggest -> graph.settings.setSuggestMemories(action.on)
            }
        }
    }

    override suspend fun save(id: String?, text: String, suggestion: String?): MemoryIssue? {
        if (suggestion != null) {
            return when (val kept = graph.memorySuggestions.keep(suggestion, text)) {
                is Outcome.Success -> null
                is Outcome.Failure -> when (keepProblem(kept.error, text, graph.memory.current().size, policy.maxItems, policy.maxItemChars)) {
                    KeepProblem.Gone -> MemoryIssue.Gone
                    KeepProblem.Full -> MemoryIssue.Full
                    KeepProblem.TooLong -> MemoryIssue.TooLong
                    KeepProblem.Failed -> MemoryIssue.Failed
                }
            }
        }
        val result = if (id == null) graph.memory.remember(text) else graph.memory.change(id, text)
        return when (result) {
            is Outcome.Success -> null
            // The store's reasons are written for the model; the page says it in the user's words.
            is Outcome.Failure -> if (id == null && graph.memory.current().size >= policy.maxItems) MemoryIssue.Full else MemoryIssue.Failed
        }
    }
}
