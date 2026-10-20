package app.whittle.connectors.mcp

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** One page of the registry's servers, and the cursor to the next. */
data class RegistryPage(val servers: List<LibraryServer>, val next: String?)

/**
 * Searches the MCP Registry for servers Whittle can reach: latest versions with a remote address. Entries
 * come from anyone who publishes there, so the app presents them as unreviewed.
 */
class McpRegistry(private val library: LibraryConfig, private val http: OkHttpClient) {
    suspend fun search(query: String, cursor: String? = null): Outcome<RegistryPage> = withContext(Dispatchers.IO) {
        val url = library.registryUrl.toHttpUrl().newBuilder()
            .addQueryParameter(VERSION, LATEST)
            .addQueryParameter(LIMIT, library.pageSize.toString())
            .apply { if (query.isNotBlank()) addQueryParameter(SEARCH, query.trim()) }
            .apply { cursor?.let { addQueryParameter(CURSOR, it) } }
            .build()
        try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use Outcome.Failure(HarnessError.Provider(response.code, "The MCP Registry answered HTTP ${response.code}"))
                val page = json.decodeFromString(Page.serializer(), response.body?.string().orEmpty())
                Outcome.Success(RegistryPage(page.servers.mapNotNull { it.server.toLibrary() }, page.metadata?.nextCursor))
            }
        } catch (exception: IOException) {
            Outcome.Failure(HarnessError.Network(exception.message ?: exception.javaClass.simpleName))
        } catch (exception: SerializationException) {
            Outcome.Failure(HarnessError.Serialization("Unreadable MCP Registry answer: ${exception.message}"))
        }
    }

    /** Streamable HTTP when offered, else SSE; addresses with placeholders ({token}) can't be used as they are. */
    private fun Server.toLibrary(): LibraryServer? {
        val remote = remotes.firstOrNull { it.type == STREAMABLE && PLACEHOLDER !in it.url }
            ?: remotes.firstOrNull { it.type == SSE && PLACEHOLDER !in it.url }
            ?: return null
        return LibraryServer(
            title = title?.takeIf { it.isNotBlank() } ?: name.substringAfterLast('/'),
            description = description.orEmpty(),
            url = remote.url,
            transport = if (remote.type == SSE) McpTransportKind.SSE else McpTransportKind.STREAMABLE_HTTP,
            websiteUrl = websiteUrl,
            headers = remote.headers,
        )
    }

    @Serializable
    private data class Page(val servers: List<Entry> = emptyList(), val metadata: Metadata? = null)

    @Serializable
    private data class Entry(val server: Server)

    @Serializable
    private data class Metadata(val nextCursor: String? = null)

    @Serializable
    private data class Server(
        val name: String,
        val title: String? = null,
        val description: String? = null,
        val websiteUrl: String? = null,
        val remotes: List<Remote> = emptyList(),
    )

    @Serializable
    private data class Remote(val type: String, val url: String, val headers: List<HeaderSpec> = emptyList())

    private companion object {
        const val VERSION = "version"
        const val LATEST = "latest"
        const val LIMIT = "limit"
        const val SEARCH = "search"
        const val CURSOR = "cursor"
        const val STREAMABLE = "streamable-http"
        const val SSE = "sse"
        const val PLACEHOLDER = "{"
        val json = Json { ignoreUnknownKeys = true }
    }
}

