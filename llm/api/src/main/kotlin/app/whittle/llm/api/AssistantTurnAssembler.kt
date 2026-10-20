package app.whittle.llm.api

import app.whittle.core.common.HarnessError
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.Role
import app.whittle.core.model.TokenUsage
import kotlinx.serialization.json.JsonElement

/** Folds a stream of [ModelEvent]s into the assistant message for one step. */
class AssistantTurnAssembler {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val reasoningStates = mutableListOf<JsonElement>()
    private val toolCalls = mutableListOf<ContentBlock.ToolCall>()

    var usage: TokenUsage = TokenUsage.Zero
        private set
    var stopReason: StopReason? = null
        private set
    var failure: HarnessError? = null
        private set

    fun accept(event: ModelEvent) {
        when (event) {
            is ModelEvent.TextDelta -> text.append(event.text)
            is ModelEvent.ReasoningDelta -> reasoning.append(event.text)
            is ModelEvent.ReasoningState -> reasoningStates += event.providerState
            is ModelEvent.ToolCallCompleted -> toolCalls += event.toolCall
            is ModelEvent.Usage -> usage += event.usage
            is ModelEvent.Completed -> stopReason = event.stopReason
            is ModelEvent.Failed -> failure = event.error
        }
    }

    fun toMessage(): Message {
        val content = buildList {
            addAll(reasoningBlocks())
            if (text.isNotEmpty()) add(ContentBlock.Text(text.toString()))
            addAll(toolCalls)
        }
        return Message(Role.ASSISTANT, content)
    }

    /** One block per provider state, in order; the readable summary text rides on the first one. */
    private fun reasoningBlocks(): List<ContentBlock.Reasoning> {
        val summary = reasoning.toString().takeIf { it.isNotEmpty() }
        if (reasoningStates.isEmpty()) return listOfNotNull(summary?.let { ContentBlock.Reasoning(text = it, providerState = null) })
        return reasoningStates.mapIndexed { index, state ->
            ContentBlock.Reasoning(text = summary.takeIf { index == 0 }, providerState = state)
        }
    }
}
