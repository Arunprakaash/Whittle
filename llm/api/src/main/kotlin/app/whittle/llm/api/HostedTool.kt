package app.whittle.llm.api

import kotlinx.serialization.Serializable

/**
 * A tool the provider runs on its own servers inside one model turn. Whittle never executes it; it only
 * offers it to the model when policy allows, and shows each use as a step.
 */
@Serializable
enum class HostedTool(val toolName: String) {
    WEB_SEARCH("web_search"),
}

/** Which of a provider's models can use [tool]. `*` in a pattern matches any run of characters. */
@Serializable
data class HostedToolSupport(
    val tool: HostedTool,
    val modelIdPatterns: List<String>,
)
