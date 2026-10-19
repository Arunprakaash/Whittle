package com.mobileagent.llm.responses

import com.mobileagent.core.common.Secret
import com.mobileagent.llm.api.ApiFormat
import com.mobileagent.llm.api.ModelAdapter
import com.mobileagent.llm.api.ModelEvent
import com.mobileagent.llm.api.ModelRequest
import com.mobileagent.llm.api.ProviderDescriptor
import com.mobileagent.llm.http.ProviderRequests
import com.mobileagent.llm.http.SseModelStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Streams from the OpenAI Responses API. Unlike Chat Completions it supports tools together with
 * reasoning, and carries encrypted reasoning between steps so the model keeps its train of thought.
 */
class OpenAiResponsesAdapter(httpClient: OkHttpClient) : ModelAdapter {
    override val apiFormat: ApiFormat = ApiFormat.OPENAI_RESPONSES

    private val mapper = ResponsesRequestMapper()
    private val sse = SseModelStream(httpClient)

    override fun stream(request: ModelRequest, provider: ProviderDescriptor, apiKey: Secret): Flow<ModelEvent> {
        val body = WireJson.encodeToString(JsonObject.serializer(), mapper.map(request, provider))
        val httpRequest = ProviderRequests.builder(provider, apiKey, provider.generationPath)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return sse.stream(httpRequest, ResponsesStreamParser())
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
