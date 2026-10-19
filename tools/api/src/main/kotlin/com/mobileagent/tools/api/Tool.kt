package com.mobileagent.tools.api

import com.mobileagent.core.model.Provenance
import com.mobileagent.core.model.RunId
import com.mobileagent.core.model.ToolCallId
import kotlinx.serialization.json.JsonObject

data class ToolExecutionContext(
    val runId: RunId,
    val toolCallId: ToolCallId,
)

data class ToolOutput(
    val content: String,
    val isError: Boolean,
    val provenance: Provenance,
)

/** A capability the agent can call. Implementations register themselves through DI. */
interface Tool {
    val definition: ToolDefinition

    suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput
}
