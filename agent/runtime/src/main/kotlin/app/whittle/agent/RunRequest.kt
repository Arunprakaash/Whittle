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
    /** The chat the run belongs to, for the workspace tools: the workspace is shared, the id ties processes, outputs and changes to the chat. */
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

    /** An IANA zone id such as Asia/Kolkata. Without it models guess a zone from the locale. */
    const val USER_TIME_ZONE = "userTimeZone"

    /** Where the chat works in the shared workspace: its project, or the whole workspace. Hosts fill it. */
    const val PROJECT = "project"

    /** What the agent remembers about the user; the runner fills it from its [MemoryStore], so hosts don't pass it. */
    const val MEMORIES = "memories"
}
