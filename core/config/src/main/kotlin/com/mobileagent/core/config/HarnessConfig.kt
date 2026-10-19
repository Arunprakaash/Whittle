package com.mobileagent.core.config

import kotlinx.serialization.Serializable

/** Root settings shared across modules. Module-specific catalogs live in their own documents. */
@Serializable
data class HarnessConfig(
    override val schemaVersion: Int,
    val agent: AgentLimits,
    val network: NetworkConfig,
    val telemetry: TelemetryConfig,
    val secrets: SecretStoreConfig,
) : VersionedConfig {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class AgentLimits(
    val maxStepsPerRun: Int,
    val maxOutputTokensPerStep: Int,
    val tokenBudgetPerRun: Long,
    val maxToolOutputChars: Int,
) {
    init {
        require(maxStepsPerRun > 0) { "maxStepsPerRun must be positive" }
        require(maxOutputTokensPerStep > 0) { "maxOutputTokensPerStep must be positive" }
        require(tokenBudgetPerRun > 0) { "tokenBudgetPerRun must be positive" }
        require(maxToolOutputChars > 0) { "maxToolOutputChars must be positive" }
    }
}

@Serializable
data class NetworkConfig(
    val connectTimeoutMillis: Long,
    val readTimeoutMillis: Long,
    val writeTimeoutMillis: Long,
)

@Serializable
enum class ConsentLevel {
    /** Nothing leaves the phone. */
    OFF,

    /** Ids, durations, token counts, error types and policy decisions only. */
    METADATA,

    /** Full content, only for a run the user explicitly chose to send. */
    FULL_CONTENT,
}

@Serializable
data class TelemetryConfig(
    val defaultConsent: ConsentLevel,
    val posthogHost: String,
    val maxAttributeChars: Int,
)

@Serializable
data class SecretStoreConfig(
    val dataStoreName: String,
    val keysetName: String,
    val keysetPreferencesFile: String,
    val masterKeyUri: String,
    val keyTemplate: String,
)
