package com.mobileagent.llm.openrouter

import com.mobileagent.core.common.Outcome
import com.mobileagent.core.common.Secret
import com.mobileagent.core.model.ProviderId
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenRouterAuthorizerTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `S256 challenge matches the RFC 7636 example`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce().challengeFor(verifier, CodeChallengeMethod.S256))
    }

    @Test
    fun `authorization url carries pkce and state`() {
        val request = authorizer().begin()
        val url = request.authorizationUrl.toHttpUrl()
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(request.state, url.queryParameter("state"))
        assertEquals("https://app.example/callback", url.queryParameter("callback_url"))
        assertTrue(request.codeVerifier.reveal().length >= MIN_VERIFIER_LENGTH)
    }

    @Test
    fun `callback with a different state is rejected`() {
        val request = authorizer().begin()
        val result = authorizer().codeFromCallback("https://app.example/callback?code=abc&state=forged", request)
        assertIs<Outcome.Failure>(result)
    }

    @Test
    fun `callback with matching state yields the code`() {
        val request = authorizer().begin()
        val result = authorizer().codeFromCallback("https://app.example/callback?code=abc&state=${request.state}", request)
        assertEquals(Outcome.Success("abc"), result)
    }

    @Test
    fun `code exchange returns the user's key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"key":"sk-or-user"}"""))
        val request = authorizer().begin()

        val result = authorizer().exchange("abc", request)

        assertEquals(Outcome.Success(Secret("sk-or-user")), result)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(request.codeVerifier.reveal()))
    }

    private fun authorizer() = OpenRouterAuthorizer(
        config = OpenRouterAuthConfig(
            schemaVersion = OpenRouterAuthConfig.SCHEMA_VERSION,
            providerId = ProviderId("openrouter"),
            authorizeUrl = "https://openrouter.example/auth",
            keyExchangeUrl = server.url("/api/v1/auth/keys").toString(),
            callbackUrl = "https://app.example/callback",
            codeChallengeMethod = CodeChallengeMethod.S256,
            keyLabel = "Test",
            verifierEntropyBytes = 64,
            stateEntropyBytes = 32,
        ),
        httpClient = OkHttpClient(),
    )

    private companion object {
        const val MIN_VERIFIER_LENGTH = 43
    }
}
