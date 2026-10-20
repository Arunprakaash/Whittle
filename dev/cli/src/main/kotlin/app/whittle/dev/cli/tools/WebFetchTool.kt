package app.whittle.dev.cli.tools

import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Sample outbound tool: fetches a web page. Its output is untrusted, so it taints the run and
 * exercises the approval and taint rules end to end.
 */
class WebFetchTool(private val httpClient: OkHttpClient) : Tool {
    override val definition = ToolDefinition(
        id = ToolId("web_fetch"),
        description = "Downloads a web page over https and returns its raw text.",
        inputSchema = Json.parseToJsonElement(
            """{"type":"object","properties":{"url":{"type":"string","description":"https URL"}},"required":["url"]}""",
        ).jsonObject,
        riskTier = RiskTier.NETWORK_OUT,
        executionSite = ExecutionSite.DEVICE,
        requiredCapabilities = emptySet(),
        consentTextKey = "consent_web_fetch",
        outputProvenance = Provenance.Untrusted(SOURCE_LABEL),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val url = arguments.getValue("url").jsonPrimitive.content.toHttpUrlOrNull()
        if (url == null || !url.isHttps) return failure("Only https URLs are allowed")
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    ToolOutput(body, isError = !response.isSuccessful, provenance = definition.outputProvenance)
                }
            } catch (exception: IOException) {
                failure(exception.message ?: exception.javaClass.simpleName)
            }
        }
    }

    private fun failure(message: String) = ToolOutput(message, isError = true, provenance = Provenance.Trusted)

    private companion object {
        const val SOURCE_LABEL = "web page"
    }
}
