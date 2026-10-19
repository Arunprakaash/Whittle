package com.mobileagent.storage.secrets

import com.mobileagent.core.common.Outcome
import com.mobileagent.core.common.Secret
import com.mobileagent.core.model.ProviderId

@JvmInline
value class SecretKey(val name: String) {
    companion object {
        private const val PROVIDER_API_KEY_PREFIX = "provider_api_key:"

        fun providerApiKey(providerId: ProviderId) = SecretKey(PROVIDER_API_KEY_PREFIX + providerId.value)
    }
}

/** On-device vault for API keys and tokens. Values never leave the phone and never reach a model or sandbox. */
interface SecretStore {
    suspend fun put(key: SecretKey, value: Secret): Outcome<Unit>

    /** Returns null when nothing is stored under [key]. */
    suspend fun get(key: SecretKey): Outcome<Secret?>

    suspend fun delete(key: SecretKey): Outcome<Unit>
}
