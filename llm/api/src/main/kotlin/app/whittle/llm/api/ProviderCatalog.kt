package app.whittle.llm.api

import app.whittle.core.config.VersionedConfig
import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The wire protocol a provider speaks. One adapter exists per format, never per provider. */
@Serializable
enum class ApiFormat {
    OPENAI_CHAT_COMPLETIONS,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI,
}

@Serializable
data class ProviderAuth(
    val headerName: String,
    val valuePrefix: String,
)

/**
 * Extra request fields for models matching [modelIdPattern] (`*` wildcard), for provider quirks such as
 * OpenAI rejecting tools with reasoning on Chat Completions unless `reasoning_effort` is "none".
 */
@Serializable
data class RequestOverride(
    val modelIdPattern: String,
    val fields: JsonObject,
)

@Serializable
data class ModelCapabilityOverride(
    val modelIdPattern: String,
    val capabilities: ModelCapabilities,
)

@Serializable
data class ProviderDescriptor(
    val id: ProviderId,
    val displayName: String,
    /** A line under the name where the user picks a provider, such as what its key gives them. */
    val tagline: String? = null,
    /** Where the user makes a key; linked from the connect screen. */
    val keysUrl: String? = null,
    val apiFormat: ApiFormat,
    val baseUrl: String,
    val generationPath: String,
    val modelsPath: String? = null,
    /** The models offered from this provider's list (`*` wildcard); empty offers every model it returns. */
    val listedModelIdPatterns: List<String> = emptyList(),
    /** An endpoint that requires a valid key, used to check one before it is saved. Model lists can be public. */
    val keyCheckPath: String? = null,
    val auth: ProviderAuth,
    /** Request field that caps output tokens; providers disagree (e.g. "max_tokens" vs "max_completion_tokens"). */
    val maxOutputTokensParameter: String,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val requestOverrides: List<RequestOverride> = emptyList(),
    /** How this provider takes a reasoning effort. Null means it takes none. */
    val reasoning: ReasoningWireConfig? = null,
    /**
     * Extra fields inside the request's thinking settings, sent whenever a model thinks. For Anthropic, `block_binding`
     * keeps a chat going when earlier thinking no longer matches its conversation (the system prompt carries the date and
     * memories, which change between runs): those blocks are dropped instead of the request failing.
     */
    val thinkingFields: JsonObject = JsonObject(emptyMap()),
    /**
     * The most a step may write with this provider, in place of the harness's per-step cap. Claude's thinking counts
     * toward it, so the harness's cap would cut its answers and tool calls short.
     */
    val maxOutputTokens: Int? = null,
    /** Capabilities for models whose provider doesn't report them; first matching pattern wins. */
    val capabilityOverrides: List<ModelCapabilityOverride> = emptyList(),
    /** Tools this provider runs itself, such as web search, and the models that support them. */
    val hostedTools: List<HostedToolSupport> = emptyList(),
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) { "$id maxOutputTokens must be positive" }
    }

    /** Whether [modelId] is offered when listing this provider's models. */
    fun lists(modelId: ModelId): Boolean =
        listedModelIdPatterns.isEmpty() || listedModelIdPatterns.any { GlobPattern(it).matches(modelId.value) }

    fun hostedToolsFor(modelId: ModelId): Set<HostedTool> =
        hostedTools.filter { support -> support.modelIdPatterns.any { GlobPattern(it).matches(modelId.value) } }
            .mapTo(mutableSetOf()) { it.tool }

    fun capabilitiesFor(modelId: ModelId): ModelCapabilities? =
        capabilityOverrides.firstOrNull { GlobPattern(it.modelIdPattern).matches(modelId.value) }?.capabilities

    /** Extra fields for [modelId]; when several overrides match, later entries win. */
    fun requestFieldsFor(modelId: ModelId): Map<String, JsonElement> =
        requestOverrides.filter { GlobPattern(it.modelIdPattern).matches(modelId.value) }
            .fold(emptyMap()) { fields, override -> fields + override.fields }
}

@Serializable
data class ProviderCatalog(
    override val schemaVersion: Int,
    val providers: List<ProviderDescriptor>,
) : VersionedConfig {
    init {
        val duplicates = providers.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate provider ids: $duplicates" }
    }

    fun find(id: ProviderId): ProviderDescriptor? = providers.firstOrNull { it.id == id }

    /** User-added providers (custom OpenAI-compatible URLs, local servers) replace bundled ones with the same id. */
    fun mergedWith(overrides: List<ProviderDescriptor>): ProviderCatalog {
        val overrideIds = overrides.map { it.id }.toSet()
        return copy(providers = providers.filterNot { it.id in overrideIds } + overrides)
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
