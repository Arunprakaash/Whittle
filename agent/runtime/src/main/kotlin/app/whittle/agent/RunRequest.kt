package app.whittle.agent

import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ReasoningEffort
import app.whittle.policy.RunMode
import app.whittle.tools.api.ToolId

data class RunRequest(
    val runId: RunId,
    val model: ModelDescriptor,
    val userMessage: Message,
    val mode: RunMode,
    val reasoningEffort: ReasoningEffort,
    val preApprovedTools: Set<ToolId>,
    /** Values for the system prompt template's placeholders (date, locale, ...). */
    val promptVariables: Map<String, String>,
)
