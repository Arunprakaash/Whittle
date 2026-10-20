package app.whittle.storage.secrets

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.flatMap
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.CredentialsProvider

/** Reads the user's provider keys from the on-device vault. */
class SecretStoreCredentialsProvider(private val store: SecretStore) : CredentialsProvider {
    override suspend fun apiKeyFor(providerId: ProviderId): Outcome<Secret> =
        store.get(SecretKey.providerApiKey(providerId)).flatMap { secret ->
            secret?.let { Outcome.Success(it) }
                ?: Outcome.Failure(HarnessError.Authentication("No key stored for provider ${providerId.value}"))
        }
}
