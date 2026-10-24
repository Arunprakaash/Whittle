package app.whittle.llm.openai

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.model.ModelId
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ModelPricing
import app.whittle.llm.api.ModelTrustConfig
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.http.ProviderRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Builds the model catalog from the provider's own model list, so model ids are never hardcoded; a provider can
 * narrow it to the models its config lists. Capabilities come from the provider's configured overrides first, then the server's own metadata
 * (OpenRouter reports it), then [fallbackCapabilities].
 */
class OpenAiCompatibleModelLister(
    private val httpClient: OkHttpClient,
    private val trustConfig: ModelTrustConfig,
    private val fallbackCapabilities: ModelCapabilities,
) {
    suspend fun listModels(provider: ProviderDescriptor, apiKey: Secret): Outcome<List<ModelDescriptor>> {
        val modelsPath = provider.modelsPath
            ?: return Outcome.Failure(HarnessError.Configuration("Provider ${provider.id.value} has no modelsPath"))
        val request = ProviderRequests.builder(provider, apiKey, modelsPath).get().build()
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@use Outcome.Failure(HarnessError.Provider(response.code, body))
                    val models = WireJson.decodeFromString(ModelListResponse.serializer(), body).data
                    Outcome.Success(models.filter { provider.lists(ModelId(it.id)) }.map { it.toDescriptor(provider) })
                }
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Network(exception.message ?: exception.javaClass.simpleName))
            } catch (exception: SerializationException) {
                Outcome.Failure(HarnessError.Serialization("Unreadable model list: ${exception.message}"))
            }
        }
    }

    private fun WireModel.toDescriptor(provider: ProviderDescriptor): ModelDescriptor {
        val modelId = ModelId(id)
        return ModelDescriptor(
            id = modelId,
            providerId = provider.id,
            displayName = name ?: displayName ?: id,
            contextWindowTokens = contextLength ?: maxInputTokens,
            capabilities = provider.capabilitiesFor(modelId) ?: capabilities(),
            trustTier = trustConfig.tierFor(modelId),
            pricing = pricing(),
        )
    }

    private fun WireModel.capabilities(): ModelCapabilities {
        val parameters = supportedParameters ?: return fallbackCapabilities
        return ModelCapabilities(
            toolCalling = TOOLS_PARAMETER in parameters,
            parallelToolCalls = PARALLEL_TOOL_CALLS_PARAMETER in parameters,
            vision = architecture?.inputModalities?.contains(IMAGE_MODALITY) ?: fallbackCapabilities.vision,
            promptCaching = fallbackCapabilities.promptCaching,
            reasoning = REASONING_PARAMETER in parameters,
        )
    }

    private fun WireModel.pricing(): ModelPricing? {
        val input = pricing?.prompt?.toDoubleOrNull() ?: return null
        val output = pricing.completion?.toDoubleOrNull() ?: return null
        return ModelPricing(input * TOKENS_PER_MILLION, output * TOKENS_PER_MILLION)
    }

    private companion object {
        const val TOOLS_PARAMETER = "tools"
        const val PARALLEL_TOOL_CALLS_PARAMETER = "parallel_tool_calls"
        const val REASONING_PARAMETER = "reasoning"
        const val IMAGE_MODALITY = "image"
        const val TOKENS_PER_MILLION = 1_000_000.0
    }
}
