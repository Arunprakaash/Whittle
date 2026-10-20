package app.whittle.connectors.mcp

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Finds out how a server the user is adding signs in: opening a session without credentials either works
 * (no sign-in), or is refused with OAuth metadata (browser sign-in), or is refused plainly (access token).
 */
class McpProbe(private val config: ConnectorsConfig, private val http: OkHttpClient) {
    suspend fun detect(url: String): Outcome<McpAuthMode> {
        // https only, except a server on this phone itself.
        val endpoint = url.toHttpUrlOrNull()?.takeIf { it.isHttps || it.host in LOOPBACK }
            ?: return Outcome.Failure(HarnessError.Configuration("A connection needs an https address"))
        val initialize = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put(
                "params",
                buildJsonObject {
                    put("protocolVersion", config.protocolVersion)
                    put("capabilities", buildJsonObject { })
                    put("clientInfo", buildJsonObject { put("name", config.clientName); put("version", config.protocolVersion) })
                },
            )
        }
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .post(initialize.toString().toRequestBody(JSON))
            .build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> Outcome.Success(McpAuthMode.NONE)
                        response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN -> {
                            val challenge = response.header("WWW-Authenticate").orEmpty()
                            Outcome.Success(if (RESOURCE_METADATA in challenge || hasOAuthMetadata(endpoint.toString())) McpAuthMode.OAUTH else McpAuthMode.TOKEN)
                        }
                        else -> Outcome.Failure(HarnessError.Provider(response.code, "The server answered HTTP ${response.code}; is this an MCP address?"))
                    }
                }
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Network(exception.message ?: exception.javaClass.simpleName))
            }
        }
    }

    private fun hasOAuthMetadata(url: String): Boolean {
        val resource = url.toHttpUrlOrNull() ?: return false
        val metadata = resource.newBuilder().encodedPath(PROTECTED_RESOURCE_PATH + resource.encodedPath.trimEnd('/')).query(null).build()
        return try {
            http.newCall(Request.Builder().url(metadata).get().build()).execute().use { it.isSuccessful }
        } catch (exception: IOException) {
            false
        }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val RESOURCE_METADATA = "resource_metadata"
        const val PROTECTED_RESOURCE_PATH = "/.well-known/oauth-protected-resource"
        val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]", "::1")
        val JSON = "application/json".toMediaType()
    }
}
