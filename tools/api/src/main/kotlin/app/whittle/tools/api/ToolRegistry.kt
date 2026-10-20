package app.whittle.tools.api

/**
 * All tools available to the agent. The agent loop only ever looks tools up here. [connected] supplies tools
 * that come and go while the app runs (a connector the user signs in to); built-in tools win any id clash.
 */
class ToolRegistry(tools: Set<Tool>, private val connected: () -> Collection<Tool> = { emptyList() }) {
    private val byId: Map<ToolId, Tool>

    init {
        val duplicates = tools.groupBy { it.definition.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool ids: ${duplicates.map { it.value }}" }
        byId = tools.associateBy { it.definition.id }
    }

    val definitions: List<ToolDefinition>
        get() = (byId.values + connected().filter { it.definition.id !in byId }).map { it.definition }.sortedBy { it.id.value }

    fun find(id: ToolId): Tool? = byId[id] ?: connected().firstOrNull { it.definition.id == id }
}
