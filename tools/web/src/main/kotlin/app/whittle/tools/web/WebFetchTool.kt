package app.whittle.tools.web

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
 * Reads a public web page over https without asking. Its output is untrusted, so it taints the run:
 * later outbound calls need the user's choice, and so do web reads once personal data is in the run.
 */
class WebFetchTool(private val httpClient: OkHttpClient) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val url = arguments.getValue("url").jsonPrimitive.content.toHttpUrlOrNull()
        if (url == null || !url.isHttps) return failure("Only https URLs are allowed")
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    // A page's markup is mostly noise to the model and too long to read whole; its text is what matters.
                    val isPage = response.body?.contentType()?.subtype?.contains(HTML) == true
                    val content = if (isPage) HtmlText.of(body, response.request.url) else body
                    ToolOutput(content, isError = !response.isSuccessful, provenance = definition.outputProvenance)
                }
            } catch (exception: IOException) {
                failure(exception.message ?: exception.javaClass.simpleName)
            }
        }
    }

    private fun failure(message: String) = ToolOutput(message, isError = true, provenance = Provenance.Trusted)

    companion object {
        val DEFINITION = ToolDefinition(
            id = ToolId("web_fetch"),
            description = "Opens a web page over https and returns its readable text, with each link's address in brackets " +
                "after its words so you can open it next. Other files come back as they are.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"url":{"type":"string","description":"https URL"}},"required":["url"]}""",
            ).jsonObject,
            riskTier = RiskTier.WEB_READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_web_fetch",
            outputProvenance = Provenance.Untrusted(SOURCE_LABEL),
        )

        private const val SOURCE_LABEL = "web page"
        private const val HTML = "html"
    }
}
