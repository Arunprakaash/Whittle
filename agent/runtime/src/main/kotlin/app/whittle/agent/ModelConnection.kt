package app.whittle.agent

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.flatMap
import app.whittle.core.common.map
import app.whittle.llm.api.CredentialsProvider
import app.whittle.llm.api.ModelAdapter
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.api.ProviderDescriptor

/** What it takes to call one model: the wire adapter, the provider it speaks to, and the user's key. */
internal class ModelConnection(
    val adapter: ModelAdapter,
    val provider: ProviderDescriptor,
    val apiKey: Secret,
)

/** Looks up the provider, adapter and key for [model]; shared by agent runs and one-off calls. */
internal suspend fun connectModel(
    adapters: ModelAdapterRegistry,
    providers: ProviderCatalog,
    credentials: CredentialsProvider,
    model: ModelDescriptor,
): Outcome<ModelConnection> {
    val provider = providers.find(model.providerId)
        ?: return Outcome.Failure(HarnessError.Configuration("Unknown provider ${model.providerId.value}"))
    return adapters.adapterFor(provider.apiFormat).flatMap { adapter ->
        credentials.apiKeyFor(provider.id).map { apiKey -> ModelConnection(adapter, provider, apiKey) }
    }
}
