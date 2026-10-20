package app.whittle.core.config

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
    /** Longest plan (update_plan) the agent may write. */
    val maxPlanItems: Int = DEFAULT_MAX_PLAN_ITEMS,
) {
    init {
        require(maxStepsPerRun > 0) { "maxStepsPerRun must be positive" }
        require(maxOutputTokensPerStep > 0) { "maxOutputTokensPerStep must be positive" }
        require(tokenBudgetPerRun > 0) { "tokenBudgetPerRun must be positive" }
        require(maxToolOutputChars > 0) { "maxToolOutputChars must be positive" }
        require(maxPlanItems > 0) { "maxPlanItems must be positive" }
    }

    private companion object {
        const val DEFAULT_MAX_PLAN_ITEMS = 12
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
    val sessionReplay: SessionReplayConfig,
)

/**
 * Screen recordings for the analytics tool. Masking happens on the phone before anything is sent, so
 * with text and images masked a recording shows layout, taps and navigation but no chat, key or page
 * content. Recordings only run when the user's consent isn't OFF.
 */
@Serializable
data class SessionReplayConfig(
    val enabled: Boolean,
    /** Compose UIs record as screenshots; the view-hierarchy wireframe mode can't see Compose content. */
    val screenshotMode: Boolean,
    /** Every text and text field drawn as a block. */
    val maskAllText: Boolean,
    val maskAllImages: Boolean,
    val captureTouches: Boolean,
    /** Logs can hold content, so they stay off unless deliberately turned on. */
    val captureLogcat: Boolean,
    /** Share of sessions recorded, 0.0 to 1.0. */
    val sampleRate: Double,
) {
    init {
        require(sampleRate in 0.0..1.0) { "sessionReplay.sampleRate must be between 0 and 1" }
    }
}

@Serializable
data class SecretStoreConfig(
    val dataStoreName: String,
    val keysetName: String,
    val keysetPreferencesFile: String,
    val masterKeyUri: String,
    val keyTemplate: String,
)
