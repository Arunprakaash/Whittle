package app.whittle.llm.openai

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.ApiFormat
import app.whittle.llm.api.ProviderAuth
import app.whittle.llm.api.ProviderDescriptor
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiKeyVerifierTest {
    private val server = MockWebServer().apply { start() }
    private val verifier = ApiKeyVerifier(OkHttpClient())
    private val provider = ProviderDescriptor(
        id = ProviderId("test"),
        displayName = "Test",
        apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,
        baseUrl = server.url("/v1/").toString(),
        generationPath = "chat",
        keyCheckPath = "key",
        auth = ProviderAuth("Authorization", "Bearer "),
        maxOutputTokensParameter = "max_tokens",
    )

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `a working key passes and is sent to the key check path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        assertIs<Outcome.Success<Unit>>(verifier.verify(provider, Secret("good")))
        val request = server.takeRequest()
        assertEquals("/v1/key", request.path)
        assertEquals("Bearer good", request.getHeader("Authorization"))
    }

    @Test
    fun `a rejected key is an authentication failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val failure = assertIs<Outcome.Failure>(verifier.verify(provider, Secret("bad")))
        assertIs<HarnessError.Authentication>(failure.error)
    }

    @Test
    fun `a provider without a key check path is a configuration error`() = runTest {
        val failure = assertIs<Outcome.Failure>(verifier.verify(provider.copy(keyCheckPath = null), Secret("any")))
        assertIs<HarnessError.Configuration>(failure.error)
    }
}
