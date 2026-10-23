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
    val maxToolOutputChars: Int,
    /** Longest plan (update_plan) the agent may write. */
    val maxPlanItems: Int = DEFAULT_MAX_PLAN_ITEMS,
    /** The most of a crash or log the user passes on with Ask to fix that reaches the model; the end is kept. */
    val maxPastedOutputChars: Int,
    val retry: ModelRetryPolicy,
    val toolOutput: ToolOutputPolicy,
    val memory: MemoryPolicy,
) {
    init {
        require(maxStepsPerRun > 0) { "maxStepsPerRun must be positive" }
        require(maxOutputTokensPerStep > 0) { "maxOutputTokensPerStep must be positive" }
        require(maxToolOutputChars > 0) { "maxToolOutputChars must be positive" }
        require(maxPlanItems > 0) { "maxPlanItems must be positive" }
        require(maxPastedOutputChars > 0) { "maxPastedOutputChars must be positive" }
    }

    private companion object {
        const val DEFAULT_MAX_PLAN_ITEMS = 12
    }
}

/** How a model call that failed for a passing reason (network, rate limit, busy server) is tried again. */
@Serializable
data class ModelRetryPolicy(
    /** Tries in all, the first included. */
    val maxAttempts: Int,
    val initialDelayMillis: Long,
    val maxDelayMillis: Long,
    /** Each wait is this many times the last, up to [maxDelayMillis]. */
    val backoffMultiplier: Double,
    /** Waits vary randomly by up to this share, so many phones don't retry in step. */
    val jitter: Double,
    /** Provider replies worth another try: timeouts, rate limits and server errors. */
    val retryableStatusCodes: Set<Int>,
) {
    init {
        require(maxAttempts >= 1) { "retry.maxAttempts must be at least 1" }
        require(initialDelayMillis >= 0 && maxDelayMillis >= initialDelayMillis) { "retry delays must be ordered" }
        require(backoffMultiplier >= 1.0) { "retry.backoffMultiplier must be at least 1" }
        require(jitter in 0.0..1.0) { "retry.jitter must be between 0 and 1" }
    }
}

/** What the agent may remember about the user across chats (the remember and forget tools). */
@Serializable
data class MemoryPolicy(
    /** Facts kept at most; when full, the agent is told to forget one first. */
    val maxItems: Int,
    val maxItemChars: Int,
    /** Length of a fact's id, which the agent passes to forget; short ids are cheap in the prompt. */
    val idChars: Int,
) {
    init {
        require(maxItems > 0 && maxItemChars > 0 && idChars > 0) { "memory limits must be positive" }
    }
}

/** Tool output too long for the conversation is saved in the chat's workspace; the model gets a preview and the path. */
@Serializable
data class ToolOutputPolicy(
    val saveAboveChars: Int,
    val previewHeadChars: Int,
    val previewTailChars: Int,
    /** Folder inside the workspace, relative to its root. */
    val directory: String,
) {
    init {
        require(saveAboveChars > 0 && previewHeadChars >= 0 && previewTailChars >= 0) { "toolOutput limits must be positive" }
        require(previewHeadChars + previewTailChars < saveAboveChars) { "toolOutput previews must be shorter than saveAboveChars" }
        require(directory.isNotBlank() && !directory.startsWith("/") && ".." !in directory.split('/')) { "toolOutput.directory must stay inside the workspace" }
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
