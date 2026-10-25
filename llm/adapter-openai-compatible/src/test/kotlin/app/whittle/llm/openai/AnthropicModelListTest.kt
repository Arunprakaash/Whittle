package app.whittle.llm.openai

import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.ApiFormat
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelCapabilityOverride
import app.whittle.llm.api.ModelTrustConfig
import app.whittle.llm.api.ProviderAuth
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.api.TrustTier
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Anthropic's model list has the same shape as OpenAI's, with its own names for a model's name and context window. */
class AnthropicModelListTest {
    private val server = MockWebServer().apply { start() }
    private val capabilities = ModelCapabilities(toolCalling = true, parallelToolCalls = true, vision = true, promptCaching = true, reasoning = true)
    private val lister = OpenAiCompatibleModelLister(OkHttpClient(), ModelTrustConfig(1, TrustTier.C, emptyList()), capabilities.copy(reasoning = false))
    private val provider = ProviderDescriptor(
        id = ProviderId("anthropic"),
        displayName = "Anthropic",
        apiFormat = ApiFormat.ANTHROPIC_MESSAGES,
        baseUrl = server.url("/").toString(),
        generationPath = "v1/messages",
        modelsPath = "v1/models?limit=100",
        listedModelIdPatterns = listOf("claude-opus-5-5"),
        auth = ProviderAuth("x-api-key", ""),
        maxOutputTokensParameter = "max_tokens",
        defaultHeaders = mapOf("anthropic-version" to "2023-06-01"),
        capabilityOverrides = listOf(ModelCapabilityOverride("claude-*", capabilities)),
    )

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `models get their display name and context window, and only listed ones are offered`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","max_input_tokens":1000000,"max_tokens":128000},""" +
                    """{"type":"model","id":"claude-3-haiku","display_name":"Claude Haiku 3"}],"has_more":false}""",
            ),
        )
        val models = assertIs<Outcome.Success<List<app.whittle.llm.api.ModelDescriptor>>>(lister.listModels(provider, Secret("sk"))).value
        val opus = models.single()
        assertEquals("Claude Opus 5.5", opus.displayName)
        assertEquals(1_000_000L, opus.contextWindowTokens)
        assertEquals(capabilities, opus.capabilities)
        val sent = server.takeRequest()
        assertEquals("/v1/models?limit=100", sent.path)
        assertEquals("sk", sent.getHeader("x-api-key"))
        assertEquals("2023-06-01", sent.getHeader("anthropic-version"))
    }
}
