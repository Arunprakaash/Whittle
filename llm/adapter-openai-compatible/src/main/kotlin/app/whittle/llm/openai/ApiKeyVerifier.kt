package app.whittle.llm.openai

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.http.ProviderRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Checks a key against the provider's configured `keyCheckPath` before it is saved. A model list
 * alone proves nothing: some providers serve it without a key.
 */
class ApiKeyVerifier(private val httpClient: OkHttpClient) {
    suspend fun verify(provider: ProviderDescriptor, apiKey: Secret): Outcome<Unit> {
        val path = provider.keyCheckPath
            ?: return Outcome.Failure(HarnessError.Configuration("Provider ${provider.id.value} has no keyCheckPath"))
        val request = ProviderRequests.builder(provider, apiKey, path).get().build()
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> Outcome.Success(Unit)
                        response.code in REJECTED -> Outcome.Failure(HarnessError.Authentication("${provider.id.value} rejected the key (${response.code})"))
                        else -> Outcome.Failure(HarnessError.Provider(response.code, response.message))
                    }
                }
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Network(exception.message ?: exception.javaClass.simpleName))
            }
        }
    }

    private companion object {
        val REJECTED = setOf(401, 403)
    }
}
