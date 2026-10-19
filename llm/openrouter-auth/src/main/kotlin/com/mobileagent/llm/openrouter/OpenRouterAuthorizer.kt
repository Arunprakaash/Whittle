package com.mobileagent.llm.openrouter

import com.mobileagent.core.common.HarnessError
import com.mobileagent.core.common.Outcome
import com.mobileagent.core.common.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** One sign-in attempt. Keep it until the callback arrives; the verifier never leaves the phone. */
data class AuthorizationRequest(
    val authorizationUrl: String,
    val codeVerifier: Secret,
    val state: String,
)

/**
 * OpenRouter sign-in with OAuth PKCE: the user approves in a browser tab and the app receives a
 * key billed to the user's own OpenRouter credits. No client secret and no backend involved.
 */
class OpenRouterAuthorizer(
    private val config: OpenRouterAuthConfig,
    private val httpClient: OkHttpClient,
    private val pkce: Pkce = Pkce(),
) {
    fun begin(): AuthorizationRequest {
        val verifier = pkce.randomToken(config.verifierEntropyBytes)
        val state = pkce.randomToken(config.stateEntropyBytes)
        val url = config.authorizeUrl.toHttpUrl().newBuilder()
            .addQueryParameter(Param.CALLBACK_URL, config.callbackUrl)
            .addQueryParameter(Param.CODE_CHALLENGE, pkce.challengeFor(verifier, config.codeChallengeMethod))
            .addQueryParameter(Param.CODE_CHALLENGE_METHOD, config.codeChallengeMethod.name)
            .addQueryParameter(Param.KEY_LABEL, config.keyLabel)
            .addQueryParameter(Param.STATE, state)
            .build()
        return AuthorizationRequest(url.toString(), Secret(verifier), state)
    }

    /** Validates the redirect and extracts the one-time code. Rejects mismatched state (CSRF). */
    fun codeFromCallback(callbackUrl: String, request: AuthorizationRequest): Outcome<String> {
        val url = callbackUrl.toHttpUrlOrNull()
            ?: return Outcome.Failure(HarnessError.Authentication("Callback is not a valid URL"))
        if (url.queryParameter(Param.STATE) != request.state) {
            return Outcome.Failure(HarnessError.Authentication("Callback state does not match this sign-in"))
        }
        val code = url.queryParameter(Param.CODE)
            ?: return Outcome.Failure(HarnessError.Authentication("Callback has no authorization code"))
        return Outcome.Success(code)
    }

    suspend fun exchange(code: String, request: AuthorizationRequest): Outcome<Secret> {
        val payload = KeyExchangeRequest(
            code = code,
            codeVerifier = request.codeVerifier.reveal(),
            codeChallengeMethod = config.codeChallengeMethod.name,
        )
        val httpRequest = Request.Builder()
            .url(config.keyExchangeUrl)
            .post(json.encodeToString(KeyExchangeRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(httpRequest).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use Outcome.Failure(HarnessError.Authentication("Key exchange failed with HTTP ${response.code}"))
                    }
                    Outcome.Success(Secret(json.decodeFromString(KeyExchangeResponse.serializer(), body).key))
                }
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Network(exception.message ?: exception.javaClass.simpleName))
            } catch (exception: SerializationException) {
                Outcome.Failure(HarnessError.Serialization("Unreadable key exchange response: ${exception.message}"))
            }
        }
    }

    @Serializable
    private data class KeyExchangeRequest(
        val code: String,
        @SerialName("code_verifier") val codeVerifier: String,
        @SerialName("code_challenge_method") val codeChallengeMethod: String,
    )

    @Serializable
    private data class KeyExchangeResponse(val key: String)

    private object Param {
        const val CALLBACK_URL = "callback_url"
        const val CODE_CHALLENGE = "code_challenge"
        const val CODE_CHALLENGE_METHOD = "code_challenge_method"
        const val KEY_LABEL = "key_label"
        const val STATE = "state"
        const val CODE = "code"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }
}
