package com.mobileagent.agent

import com.mobileagent.core.model.Message
import com.mobileagent.core.model.RunId
import com.mobileagent.llm.api.ModelDescriptor
import com.mobileagent.llm.api.ReasoningEffort
import com.mobileagent.policy.RunMode
import com.mobileagent.tools.api.ToolId

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
