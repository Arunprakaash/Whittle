package com.mobileagent.llm.api

import com.mobileagent.core.config.VersionedConfig
import com.mobileagent.core.model.ModelId
import com.mobileagent.core.model.ProviderId
import kotlinx.serialization.Serializable

/** How much the harness trusts a model with risky tools. Decided by the injection test, never by name alone. */
@Serializable
enum class TrustTier { A, B, C }

@Serializable
data class ModelCapabilities(
    val toolCalling: Boolean,
    val parallelToolCalls: Boolean,
    val vision: Boolean,
    val promptCaching: Boolean,
    val reasoning: Boolean,
)

@Serializable
data class ModelPricing(
    val inputUsdPerMillionTokens: Double,
    val outputUsdPerMillionTokens: Double,
)

@Serializable
data class ModelDescriptor(
    val id: ModelId,
    val providerId: ProviderId,
    val displayName: String,
    val contextWindowTokens: Long?,
    val capabilities: ModelCapabilities,
    val trustTier: TrustTier,
    val pricing: ModelPricing? = null,
)

@Serializable
data class TrustTierOverride(
    /** Model id pattern; `*` matches any run of characters. */
    val modelIdPattern: String,
    val tier: TrustTier,
)

/** Trust tiers per model. Unknown models get [defaultTier] until they pass the trust-tier test. */
@Serializable
data class ModelTrustConfig(
    override val schemaVersion: Int,
    val defaultTier: TrustTier,
    val overrides: List<TrustTierOverride>,
) : VersionedConfig {
    fun tierFor(modelId: ModelId): TrustTier =
        overrides.firstOrNull { GlobPattern(it.modelIdPattern).matches(modelId.value) }?.tier ?: defaultTier

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Capabilities assumed for models whose provider reports none (e.g. OpenAI's plain /models list). */
@Serializable
data class ModelDefaults(
    override val schemaVersion: Int,
    val fallbackCapabilities: ModelCapabilities,
    /** Used when the user hasn't chosen an effort for a run. */
    val defaultReasoningEffort: ReasoningEffort,
) : VersionedConfig {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
