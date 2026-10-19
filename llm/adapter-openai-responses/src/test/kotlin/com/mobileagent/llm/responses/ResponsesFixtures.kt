package com.mobileagent.llm.responses

import com.mobileagent.core.model.ModelId
import com.mobileagent.core.model.ProviderId
import com.mobileagent.llm.api.ApiFormat
import com.mobileagent.llm.api.ModelCapabilities
import com.mobileagent.llm.api.ModelDescriptor
import com.mobileagent.llm.api.ProviderAuth
import com.mobileagent.llm.api.ProviderDescriptor
import com.mobileagent.llm.api.ReasoningEffort
import com.mobileagent.llm.api.ReasoningWireConfig
import com.mobileagent.llm.api.TrustTier

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
