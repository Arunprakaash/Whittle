package app.whittle.llm.responses

import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.ApiFormat
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ProviderAuth
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.api.ReasoningEffort
import app.whittle.llm.api.ReasoningWireConfig
import app.whittle.llm.api.TrustTier

internal fun responsesProvider(baseUrl: String = "https://api.test/v1/") = ProviderDescriptor(
    id = ProviderId("openai"),
    displayName = "OpenAI",
    apiFormat = ApiFormat.OPENAI_RESPONSES,
    baseUrl = baseUrl,
    generationPath = "responses",
    auth = ProviderAuth("Authorization", "Bearer "),
    maxOutputTokensParameter = "max_output_tokens",
    reasoning = ReasoningWireConfig(
        effortFieldPath = listOf("reasoning", "effort"),
        effortValues = ReasoningEffort.entries.associateWith { it.name.lowercase() },
    ),
)

internal fun reasoningModel(reasoning: Boolean = true) = ModelDescriptor(
    id = ModelId("gpt-test"),
    providerId = ProviderId("openai"),
    displayName = "GPT test",
    contextWindowTokens = null,
    capabilities = ModelCapabilities(toolCalling = true, parallelToolCalls = true, vision = true, promptCaching = true, reasoning = reasoning),
    trustTier = TrustTier.A,
)
