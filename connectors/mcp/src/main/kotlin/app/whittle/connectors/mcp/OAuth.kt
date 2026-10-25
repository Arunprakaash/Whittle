package app.whittle.connectors.mcp

import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Pkce
import app.whittle.core.common.Secret
import app.whittle.core.common.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Whittle's registration with a server's sign-in, made on this phone the first time the user connects. */
@Serializable
data class OAuthClient(
    val clientId: String,
    val clientSecret: String? = null,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String,
    /** What the server says it supports, asked for when the connection names no scopes of its own. */
    val scopes: List<String> = emptyList(),
)

/**
 * A client the user registered with the service themselves, for a service that doesn't let apps register
 * on their own. Used in place of registering (the MCP spec's pre-registration).
 */
@Serializable
data class OAuthClientCredentials(val clientId: String, val clientSecret: String? = null)

@Serializable
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long? = null,
)

/**
 * Everything kept for one connector, encrypted in the secret store: the OAuth registration and tokens, or
 * [open] for a server signed in by [headers] the user gave (an API key) or needing no sign-in at all.
 */
@Serializable
data class McpCredentials(
    val client: OAuthClient? = null,
    val tokens: OAuthTokens? = null,
    val open: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    /** The client the user gave, if any; [client] is then made from it instead of registering. */
    val preRegistered: OAuthClientCredentials? = null,
    /** The service ended the sign-in (a refused token): the user is asked to sign in again rather than quietly signed out. */
    val signedOut: Boolean = false,
    /** The user was told about this sign-out (see [McpConnection.takeSignOutNotice]); cleared by the next one. */
    val signOutNoticed: Boolean = false,
)

/** One sign-in in progress: kept until the browser sends the user back. The verifier never leaves the phone. */
class PendingSignIn(val authorizationUrl: String, val client: OAuthClient, val verifier: Secret, val state: String)

/**
 * OAuth 2.1 for remote MCP servers, as the MCP spec describes it: find the server's sign-in from its
 * metadata, use the client the user gave or else register Whittle there (dynamic client registration), sign
 * in with PKCE in the browser, and keep the tokens fresh. No Whittle backend is involved; the user's tokens
 * stay on the phone.
 */
class McpAuthorizer(
    private val server: McpServerConfig,
    private val config: ConnectorsConfig,
    private val http: OkHttpClient,
    private val clock: EpochClock,
    private val pkce: Pkce = Pkce(),
) {
    /**
     * Starts a sign-in with [registered] if it still fits, else with the client the user gave
     * ([preRegistered]), else by registering Whittle with the server.
     */
    suspend fun begin(registered: OAuthClient?, preRegistered: OAuthClientCredentials? = null): Outcome<PendingSignIn> {
        val fits = registered?.takeIf { it.redirectUri == server.redirectUri && (preRegistered == null || it.clientId == preRegistered.clientId) }
        val client = fits ?: when (val created = client(preRegistered)) {
            is Outcome.Success -> created.value
            is Outcome.Failure -> return created
        }
        val scopes = server.scopes.ifEmpty { client.scopes }
        val verifier = pkce.randomToken(config.verifierEntropyBytes)
        val state = pkce.randomToken(config.stateEntropyBytes)
        val url = client.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter(Param.RESPONSE_TYPE, CODE)
            .addQueryParameter(Param.CLIENT_ID, client.clientId)
            .addQueryParameter(Param.REDIRECT_URI, client.redirectUri)
            .addQueryParameter(Param.CODE_CHALLENGE, pkce.s256Challenge(verifier))
            .addQueryParameter(Param.CODE_CHALLENGE_METHOD, S256)
            .addQueryParameter(Param.STATE, state)
            .addQueryParameter(Param.RESOURCE, server.url)
            .apply { if (scopes.isNotEmpty()) addQueryParameter(Param.SCOPE, scopes.joinToString(" ")) }
            .build()
        return Outcome.Success(PendingSignIn(url.toString(), client, Secret(verifier), state))
    }

    /** Checks the browser's redirect belongs to [pending] (CSRF) and takes its one-time code. */
    fun codeFrom(callbackUrl: String, pending: PendingSignIn): Outcome<String> {
        val query = callbackUrl.substringAfter('?', "").let { "$PARSE_BASE?$it".toHttpUrlOrNull() }
            ?: return authFailure("The sign-in came back unreadable")
        if (query.queryParameter(Param.STATE) != pending.state) return authFailure("The sign-in came back for a different attempt")
        query.queryParameter(Param.ERROR)?.let { error ->
            return authFailure(query.queryParameter(Param.ERROR_DESCRIPTION) ?: error)
        }
        return query.queryParameter(CODE)?.let { Outcome.Success(it) } ?: authFailure("The sign-in came back without a code")
    }

    suspend fun exchange(code: String, pending: PendingSignIn): Outcome<OAuthTokens> = token(
        pending.client,
        FormBody.Builder()
            .add(Param.GRANT_TYPE, GRANT_AUTHORIZATION_CODE)
            .add(CODE, code)
            .add(Param.REDIRECT_URI, pending.client.redirectUri)
            .add(Param.CODE_VERIFIER, pending.verifier.reveal())
            .add(Param.RESOURCE, server.url),
        previousRefreshToken = null,
    )

    suspend fun refresh(client: OAuthClient, tokens: OAuthTokens): Outcome<OAuthTokens> {
        val refreshToken = tokens.refreshToken ?: return authFailure("Signed out; connect ${server.name} again")
        return token(
            client,
            FormBody.Builder()
                .add(Param.GRANT_TYPE, GRANT_REFRESH_TOKEN)
                .add(Param.REFRESH_TOKEN, refreshToken)
                .add(Param.RESOURCE, server.url),
            previousRefreshToken = refreshToken,
        )
    }

    /** The spec's order: the user's own client first, then registering Whittle. */
    private suspend fun client(preRegistered: OAuthClientCredentials?): Outcome<OAuthClient> {
        val (metadata, scopes) = when (val found = discover()) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }
        if (preRegistered != null) {
            return Outcome.Success(
                OAuthClient(preRegistered.clientId, preRegistered.clientSecret, metadata.authorizationEndpoint, metadata.tokenEndpoint, server.redirectUri, scopes),
            )
        }
        val registrationEndpoint = metadata.registrationEndpoint ?: return authFailure(
            "${server.name} doesn't let apps register for sign-in. Register an app with it, then add its client ID to this connection",
        )
        val request = ClientRegistrationRequest(
            clientName = config.clientName,
            redirectUris = listOf(server.redirectUri),
            grantTypes = listOf(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN),
            responseTypes = listOf(CODE),
            tokenEndpointAuthMethod = AUTH_NONE,
        )
        val body = json.encodeToString(ClientRegistrationRequest.serializer(), request).toRequestBody(JSON)
        return send(Request.Builder().url(registrationEndpoint).post(body).build(), ClientRegistration.serializer()).map {
            OAuthClient(it.clientId, it.clientSecret, metadata.authorizationEndpoint, metadata.tokenEndpoint, server.redirectUri, scopes)
        }
    }

    /**
     * Server metadata (RFC 9728) names the sign-in and its scopes; that sign-in's metadata (RFC 8414, or
     * OpenID discovery) names the endpoints. Each is looked for where the MCP spec says, in its order.
     */
    private suspend fun discover(): Outcome<Pair<AuthorizationServerMetadata, List<String>>> {
        val resource = server.url.toHttpUrl()
        val resourcePath = resource.encodedPath.trimEnd('/')
        val protectedCandidates = listOf(PROTECTED_RESOURCE_PATH + resourcePath, PROTECTED_RESOURCE_PATH)
            .distinct()
            .map { resource.newBuilder().encodedPath(it).query(null).build().toString() }
        val protected = when (val found = firstOf(protectedCandidates, ProtectedResourceMetadata.serializer())) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }
        val issuer = protected.authorizationServers.firstOrNull() ?: return authFailure("${server.name} names no sign-in server")
        val issuerUrl = issuer.toHttpUrlOrNull() ?: return authFailure("${server.name} names an unreadable sign-in server")
        val issuerPath = issuerUrl.encodedPath.trimEnd('/')
        val metadataCandidates = buildList {
            add(issuerUrl.newBuilder().encodedPath(AUTHORIZATION_SERVER_PATH + issuerPath).build().toString())
            add(issuerUrl.newBuilder().encodedPath(OPENID_CONFIGURATION_PATH + issuerPath).build().toString())
            if (issuerPath.isNotEmpty()) add(issuerUrl.newBuilder().encodedPath(issuerPath + OPENID_CONFIGURATION_PATH).build().toString())
        }
        return firstOf(metadataCandidates, AuthorizationServerMetadata.serializer()).map { it to protected.scopesSupported }
    }

    /** The first of [urls] that answers with a readable [serializer] document; else the first failure. */
    private suspend fun <T> firstOf(urls: List<String>, serializer: KSerializer<T>): Outcome<T> {
        var failure: Outcome.Failure? = null
        for (url in urls) {
            when (val found = send(Request.Builder().url(url).get().build(), serializer)) {
                is Outcome.Success -> return found
                is Outcome.Failure -> if (failure == null) failure = found
            }
        }
        return failure ?: authFailure("${server.name} has no sign-in details")
    }

    private suspend fun token(client: OAuthClient, form: FormBody.Builder, previousRefreshToken: String?): Outcome<OAuthTokens> {
        val request = Request.Builder().url(client.tokenEndpoint)
        if (client.clientSecret != null) {
            request.header(AUTHORIZATION, Credentials.basic(client.clientId, client.clientSecret))
        } else {
            form.add(Param.CLIENT_ID, client.clientId)
        }
        val sent = clock.nowMillis()
        return send(request.post(form.build()).build(), TokenResponse.serializer()).map { response ->
            OAuthTokens(
                accessToken = response.accessToken,
                // Servers may keep the old refresh token instead of rotating it.
                refreshToken = response.refreshToken ?: previousRefreshToken,
                expiresAtMillis = response.expiresIn?.let { sent + it * MILLIS_PER_SECOND },
            )
        }
    }

    private suspend fun <T> send(request: Request, serializer: KSerializer<T>): Outcome<T> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) return@use Outcome.Success(json.decodeFromString(serializer, body))
                // A sign-in server that is down, busy or slow hasn't refused anything; the sign-in stays and is tried again later.
                if (response.code >= HTTP_SERVER_ERROR || response.code in BUSY) {
                    return@use Outcome.Failure(HarnessError.Network("${server.name} sign-in isn't answering (HTTP ${response.code})"))
                }
                val reason = runCatching { json.decodeFromString(OAuthError.serializer(), body) }.getOrNull()
                val message = reason?.description ?: reason?.error ?: "${server.name} sign-in failed with HTTP ${response.code}"
                // Only a refusal (RFC 6749 §5.2: 400 or 401, e.g. invalid_grant) means the user must sign in again.
                if (response.code in REFUSED) authFailure(message) else Outcome.Failure(HarnessError.Provider(response.code, message))
            }
        } catch (exception: IOException) {
            Outcome.Failure(HarnessError.Network(exception.message ?: exception.javaClass.simpleName))
        } catch (exception: SerializationException) {
            Outcome.Failure(HarnessError.Serialization("Unreadable ${server.name} sign-in response: ${exception.message}"))
        }
    }

    private fun authFailure(message: String): Outcome.Failure = Outcome.Failure(HarnessError.Authentication(message))

    @Serializable
    private data class ProtectedResourceMetadata(
        @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
        @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    )

    @Serializable
    private data class AuthorizationServerMetadata(
        @SerialName("authorization_endpoint") val authorizationEndpoint: String,
        @SerialName("token_endpoint") val tokenEndpoint: String,
        @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    )

    @Serializable
    private data class ClientRegistrationRequest(
        @SerialName("client_name") val clientName: String,
        @SerialName("redirect_uris") val redirectUris: List<String>,
        @SerialName("grant_types") val grantTypes: List<String>,
        @SerialName("response_types") val responseTypes: List<String>,
        @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String,
    )

    @Serializable
    private data class ClientRegistration(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String? = null,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
    )

    @Serializable
    private data class OAuthError(val error: String? = null, @SerialName("error_description") val description: String? = null)

    private object Param {
        const val RESPONSE_TYPE = "response_type"
        const val CLIENT_ID = "client_id"
        const val REDIRECT_URI = "redirect_uri"
        const val CODE_CHALLENGE = "code_challenge"
        const val CODE_CHALLENGE_METHOD = "code_challenge_method"
        const val CODE_VERIFIER = "code_verifier"
        const val STATE = "state"
        const val SCOPE = "scope"
        const val RESOURCE = "resource"
        const val GRANT_TYPE = "grant_type"
        const val REFRESH_TOKEN = "refresh_token"
        const val ERROR = "error"
        const val ERROR_DESCRIPTION = "error_description"
    }

    private companion object {
        const val HTTP_SERVER_ERROR = 500

        /** Request Timeout and Too Many Requests: ask again later. */
        val BUSY = setOf(408, 429)

        /** How a token endpoint refuses a grant or a client. */
        val REFUSED = setOf(400, 401)

        const val CODE = "code"
        const val S256 = "S256"
        const val AUTH_NONE = "none"
        const val GRANT_AUTHORIZATION_CODE = "authorization_code"
        const val GRANT_REFRESH_TOKEN = "refresh_token"
        const val AUTHORIZATION = "Authorization"
        const val PROTECTED_RESOURCE_PATH = "/.well-known/oauth-protected-resource"
        const val AUTHORIZATION_SERVER_PATH = "/.well-known/oauth-authorization-server"
        const val OPENID_CONFIGURATION_PATH = "/.well-known/openid-configuration"
        const val MILLIS_PER_SECOND = 1000L

        /** Redirects use the app's own scheme, which OkHttp can't parse; only their query matters. */
        const val PARSE_BASE = "https://redirect.invalid/"
        val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

