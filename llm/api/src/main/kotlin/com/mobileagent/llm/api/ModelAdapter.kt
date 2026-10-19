package com.mobileagent.llm.api

import com.mobileagent.core.common.HarnessError
import com.mobileagent.core.common.Outcome
import com.mobileagent.core.common.Secret
import com.mobileagent.core.model.ProviderId
import kotlinx.coroutines.flow.Flow

/** Talks one wire format. Provider differences come from [ProviderDescriptor] and [ModelCapabilities]. */
interface ModelAdapter {
    val apiFormat: ApiFormat

    fun stream(request: ModelRequest, provider: ProviderDescriptor, apiKey: Secret): Flow<ModelEvent>
}

/** Supplies the user's key for a provider. Keys live only in the on-device secrets vault. */
fun interface CredentialsProvider {
    suspend fun apiKeyFor(providerId: ProviderId): Outcome<Secret>
}

class ModelAdapterRegistry(adapters: Set<ModelAdapter>) {
    private val byFormat: Map<ApiFormat, ModelAdapter> = adapters.associateBy { it.apiFormat }

    init {
        require(byFormat.size == adapters.size) { "Two adapters registered for the same API format" }
    }

    fun adapterFor(format: ApiFormat): Outcome<ModelAdapter> =
        byFormat[format]?.let { Outcome.Success(it) }
            ?: Outcome.Failure(HarnessError.Configuration("No adapter registered for $format"))
}
