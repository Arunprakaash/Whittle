package app.whittle.connectors.mcp

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * How another MCP client on the phone reaches a connected server as the user: its address and the headers that
 * sign in. The headers hold the user's tokens, so [toString] leaves them out.
 */
class McpAccess(
    val id: String,
    /** Whittle's tool ids for the server's tools start with this. */
    val toolPrefix: String,
    val url: String,
    val transport: McpTransportKind,
    val headers: Map<String, String>,
    /** The server's tools the user switched off. */
    val excludedTools: Set<String>,
) {
    override fun toString(): String = "McpAccess(id=$id, url=$url, transport=$transport, headers=${headers.keys})"
}

/** The server's address with the query that picks its tool groups. */
internal val McpServerConfig.endpoint: HttpUrl
    get() = url.toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
