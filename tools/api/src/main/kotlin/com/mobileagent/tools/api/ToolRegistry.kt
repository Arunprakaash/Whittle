package com.mobileagent.tools.api

/** All tools available to the agent. The agent loop only ever looks tools up here. */
class ToolRegistry(tools: Set<Tool>) {
    private val byId: Map<ToolId, Tool>

    init {
        val duplicates = tools.groupBy { it.definition.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool ids: ${duplicates.map { it.value }}" }
        byId = tools.associateBy { it.definition.id }
    }

    val definitions: List<ToolDefinition>
        get() = byId.values.map { it.definition }.sortedBy { it.id.value }

    fun find(id: ToolId): Tool? = byId[id]
}
