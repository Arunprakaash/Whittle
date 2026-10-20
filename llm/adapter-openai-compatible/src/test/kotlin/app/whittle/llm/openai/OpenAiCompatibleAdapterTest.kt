package app.whittle.llm.openai

import app.whittle.core.common.HarnessError
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Secret
import app.whittle.core.model.Message
import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.ApiFormat
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ProviderAuth
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.api.ReasoningEffort
import app.whittle.llm.api.ReasoningWireConfig
import app.whittle.llm.api.RequestOverride
import app.whittle.llm.api.ToolSpec
import app.whittle.llm.api.TrustTier
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleAdapterTest {
    private val server = MockWebServer()
    private val adapter = OpenAiCompatibleAdapter(OkHttpClient(), IdGenerator { "id" })

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `sends a provider-shaped request and streams events`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )

        val events = adapter.stream(request(), provider(), Secret("sk-test")).toList()

        assertEquals(listOf(ModelEvent.TextDelta("Hi")), events.filterIsInstance<ModelEvent.TextDelta>())
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertEquals("harness-test", recorded.getHeader("X-Title"))
        val body = WireJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(256, body.getValue("max_completion_tokens").jsonPrimitive.int)
        assertEquals("system", body.getValue("messages").jsonArray.first().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(1, body.getValue("tools").jsonArray.size)
        assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("high", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `http error becomes a provider failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))

        val failure = assertIs<ModelEvent.Failed>(adapter.stream(request(), provider(), Secret("bad")).toList().single())

        val error = assertIs<HarnessError.Provider>(failure.error)
        assertEquals(401, error.statusCode)
        assertTrue(error.message.contains("bad key"))
    }

    private fun provider() = ProviderDescriptor(
        id = ProviderId("test"),
        displayName = "Test",
        apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,
        baseUrl = server.url("/v1/").toString(),
        generationPath = "chat/completions",
        auth = ProviderAuth(headerName = "Authorization", valuePrefix = "Bearer "),
        maxOutputTokensParameter = "max_completion_tokens",
        defaultHeaders = mapOf("X-Title" to "harness-test"),
        reasoning = ReasoningWireConfig(
            effortFieldPath = listOf("reasoning", "effort"),
            effortValues = mapOf(ReasoningEffort.HIGH to "high"),
        ),
        requestOverrides = listOf(
            RequestOverride("test-*", buildJsonObject { put("reasoning_effort", "none") }),
            RequestOverride("other-*", buildJsonObject { put("reasoning_effort", "high") }),
            RequestOverride("test-*", buildJsonObject { put("model", "must-not-replace") }),
        ),
    )

    private fun request() = ModelRequest(
        model = ModelDescriptor(
            id = ModelId("test-model"),
            providerId = ProviderId("test"),
            displayName = "Test model",
            contextWindowTokens = null,
            capabilities = ModelCapabilities(toolCalling = true, parallelToolCalls = false, vision = false, promptCaching = false, reasoning = true),
            trustTier = TrustTier.C,
        ),
        systemPrompt = "Be helpful.",
        messages = listOf(Message.userText("Hello")),
        tools = listOf(ToolSpec("time_now", "Current time", JsonObject(emptyMap()))),
        maxOutputTokens = 256,
        reasoningEffort = ReasoningEffort.HIGH,
    )
}
