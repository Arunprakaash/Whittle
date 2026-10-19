package com.mobileagent.llm.openai

import com.mobileagent.core.common.IdGenerator
import com.mobileagent.core.common.Secret
import com.mobileagent.llm.api.ApiFormat
import com.mobileagent.llm.api.ModelAdapter
import com.mobileagent.llm.api.ModelEvent
import com.mobileagent.llm.api.ModelRequest
import com.mobileagent.llm.api.ProviderDescriptor
import com.mobileagent.llm.api.ReasoningFieldWriter
import com.mobileagent.llm.http.ProviderRequests
import com.mobileagent.llm.http.SseModelStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/** Streams from any provider that speaks the OpenAI Chat Completions format, OpenRouter included. */
class OpenAiCompatibleAdapter(
    httpClient: OkHttpClient,
    private val idGenerator: IdGenerator,
) : ModelAdapter {
    override val apiFormat: ApiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS

    private val mapper = OpenAiRequestMapper()
    private val sse = SseModelStream(httpClient)

    override fun stream(request: ModelRequest, provider: ProviderDescriptor, apiKey: Secret): Flow<ModelEvent> {
        val httpRequest = ProviderRequests.builder(provider, apiKey, provider.generationPath)
            .post(requestBody(request, provider).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return sse.stream(httpRequest, OpenAiStreamParser(WireJson, idGenerator))
    }

    /**
     * Adds the provider-specific output-token cap, configured request overrides and the reasoning effort.
     * Overrides can't replace fields the mapper already set (model, messages, tools, stream).
     */
    private fun requestBody(request: ModelRequest, provider: ProviderDescriptor): String {
        val wire = WireJson.encodeToJsonElement(ChatCompletionRequest.serializer(), mapper.map(request)).jsonObject
        val overrides = provider.requestFieldsFor(request.model.id).filterKeys { it !in wire }
        val tokenCap = provider.maxOutputTokensParameter to JsonPrimitive(request.maxOutputTokens)
        val body = ReasoningFieldWriter.apply(JsonObject(wire + tokenCap + overrides), provider, request)
        return WireJson.encodeToString(JsonObject.serializer(), body)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
