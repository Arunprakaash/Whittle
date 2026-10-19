package com.mobileagent.llm.openrouter

import com.mobileagent.core.config.VersionedConfig
import com.mobileagent.core.model.ProviderId
import kotlinx.serialization.Serializable

@Serializable
enum class CodeChallengeMethod { S256 }

@Serializable
data class OpenRouterAuthConfig(
    override val schemaVersion: Int,
    val providerId: ProviderId,
    val authorizeUrl: String,
    val keyExchangeUrl: String,
    /** Must be an https App Link or a localhost URL; OpenRouter doesn't accept custom schemes. */
    val callbackUrl: String,
    val codeChallengeMethod: CodeChallengeMethod,
    val keyLabel: String,
    val verifierEntropyBytes: Int,
    val stateEntropyBytes: Int,
) : VersionedConfig {
    init {
        require(verifierEntropyBytes in PKCE_MIN_ENTROPY_BYTES..PKCE_MAX_ENTROPY_BYTES) {
            "verifierEntropyBytes must produce a 43–128 character verifier (RFC 7636)"
        }
        require(stateEntropyBytes > 0) { "stateEntropyBytes must be positive" }
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /** 32 bytes encode to 43 base64url characters and 96 bytes to 128 (RFC 7636 §4.1 limits). */
        private const val PKCE_MIN_ENTROPY_BYTES = 32
        private const val PKCE_MAX_ENTROPY_BYTES = 96
    }
}
