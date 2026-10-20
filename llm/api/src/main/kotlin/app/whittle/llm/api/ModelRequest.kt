package app.whittle.llm.api

import app.whittle.core.model.Message
import kotlinx.serialization.json.JsonObject

/** A tool as the model sees it: name, description and JSON Schema for its arguments. */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class ModelRequest(
    val model: ModelDescriptor,
    val systemPrompt: String,
    val messages: List<Message>,
    val tools: List<ToolSpec>,
    val maxOutputTokens: Int,
    val reasoningEffort: ReasoningEffort,
    /** Provider-run tools to offer; adapters whose format has none ignore this. */
    val hostedTools: Set<HostedTool> = emptySet(),
)
