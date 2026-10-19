package com.mobileagent.storage.secrets

import com.mobileagent.core.common.HarnessError
import com.mobileagent.core.common.Outcome
import com.mobileagent.core.common.Secret
import com.mobileagent.core.common.flatMap
import com.mobileagent.core.model.ProviderId
import com.mobileagent.llm.api.CredentialsProvider

/** Reads the user's provider keys from the on-device vault. */
class SecretStoreCredentialsProvider(private val store: SecretStore) : CredentialsProvider {
    override suspend fun apiKeyFor(providerId: ProviderId): Outcome<Secret> =
        store.get(SecretKey.providerApiKey(providerId)).flatMap { secret ->
            secret?.let { Outcome.Success(it) }
                ?: Outcome.Failure(HarnessError.Authentication("No key stored for provider ${providerId.value}"))
        }
}
