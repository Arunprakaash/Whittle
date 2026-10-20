package app.whittle.agent

import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ReasoningEffort
import app.whittle.policy.RunMode
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.WorkspaceId

data class RunRequest(
    val runId: RunId,
    val model: ModelDescriptor,
    val userMessage: Message,
    /** Earlier turns of the same chat, so a follow-up continues the conversation. Empty for a new chat. */
    val priorMessages: List<Message> = emptyList(),
    /** Where workspace tools read, write and run commands. Usually one per chat. */
    val workspace: WorkspaceId? = null,
    val mode: RunMode,
    val reasoningEffort: ReasoningEffort,
    val preApprovedTools: Set<ToolId>,
    /** Values for the system prompt template's placeholders (date, locale, ...). */
    val promptVariables: Map<String, String>,
)

/** Placeholders the bundled system prompt expects in [RunRequest.promptVariables]. */
object SystemPromptVariables {
    const val CURRENT_DATE = "currentDate"
    const val USER_LOCALE = "userLocale"
}
