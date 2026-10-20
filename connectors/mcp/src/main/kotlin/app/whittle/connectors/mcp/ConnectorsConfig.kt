package app.whittle.connectors.mcp

import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import app.whittle.tools.api.RiskTier
import kotlinx.serialization.Serializable

/** Remote MCP servers the user can connect with their own account, signed in on the phone with OAuth. */
@Serializable
data class ConnectorsConfig(
    override val schemaVersion: Int,
    /** The MCP protocol revision Whittle speaks. */
    val protocolVersion: String,
    /** How Whittle names itself when it registers with a server's sign-in. */
    val clientName: String,
    val verifierEntropyBytes: Int,
    val stateEntropyBytes: Int,
    /** A token this close to expiring is refreshed before it's used. */
    val refreshBeforeExpiryMillis: Long,
    /** Longer tool results are cut, so one call can't fill the model's context. */
    val maxResultChars: Int,
    val servers: List<McpServerConfig>,
    /** How servers the user adds themselves are set up. */
    val custom: CustomConnectorDefaults,
    /** Where the user browses servers to add. */
    val library: LibraryConfig,
) : VersionedConfig {
    init {
        require(verifierEntropyBytes in PKCE_MIN_ENTROPY_BYTES..PKCE_MAX_ENTROPY_BYTES) { "verifierEntropyBytes must give a 43–128 character verifier" }
        require(stateEntropyBytes > 0 && maxResultChars > 0) { "connector limits must be positive" }
        require(servers.map { it.id }.toSet().size == servers.size) { "connector ids must be unique" }
        require(servers.map { it.toolPrefix }.toSet().size == servers.size) { "connector tool prefixes must be unique" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("connectors.json")

        /** 32 bytes encode to 43 base64url characters and 96 bytes to 128 (RFC 7636 §4.1). */
        private const val PKCE_MIN_ENTROPY_BYTES = 32
        private const val PKCE_MAX_ENTROPY_BYTES = 96
    }
}

/** How messages reach a server. */
@Serializable
enum class McpTransportKind {
    /** One URL for every message (MCP 2025-03-26 and later). */
    STREAMABLE_HTTP,

    /** The older event stream plus message endpoint (MCP 2024-11-05). */
    SSE,
}

/** A header a server needs from the user, such as an API key. */
@Serializable
data class HeaderSpec(
    val name: String,
    val description: String? = null,
    val isRequired: Boolean = false,
    val isSecret: Boolean = false,
)

/** A server to add: one Whittle picked for its library, or a match from the MCP Registry. */
@Serializable
data class LibraryServer(
    val title: String,
    val description: String,
    val url: String,
    val transport: McpTransportKind = McpTransportKind.STREAMABLE_HTTP,
    val websiteUrl: String? = null,
    val headers: List<HeaderSpec> = emptyList(),
    /** The library group it's listed under (a [LibraryCategory] id); null for registry matches. */
    val category: String? = null,
    /** Other words people search for it by, e.g. the apps a hub like Zapier reaches. */
    val keywords: List<String> = emptyList(),
)

/** A group in the library, in the order the user sees them. */
@Serializable
data class LibraryCategory(val id: String, val title: String)

@Serializable
data class LibraryConfig(
    /** The MCP Registry's server list (registry.modelcontextprotocol.io). Anyone can publish there; Whittle says so. */
    val registryUrl: String,
    val pageSize: Int,
    val categories: List<LibraryCategory>,
    /**
     * The library: official, hosted servers that sign Whittle in on their own (OAuth with dynamic client
     * registration) or need no sign-in. Anything else the user adds by its address.
     */
    val servers: List<LibraryServer>,
) {
    init {
        val ids = categories.map { it.id }
        require(ids.toSet().size == ids.size) { "library category ids must be unique" }
        servers.forEach { require(it.category in ids) { "library server ${it.title} has no known category" } }
        require(servers.map { it.url }.toSet().size == servers.size) { "library server addresses must be unique" }
    }
}

/** How a server signs Whittle in. */
@Serializable
enum class McpAuthMode {
    /** OAuth in the browser, Whittle registering itself (MCP authorization). */
    OAUTH,

    /** A key the user pastes once (as a bearer token or the header the server names); kept encrypted. */
    TOKEN,

    /** No sign-in. */
    NONE,
}

@Serializable
data class CustomConnectorDefaults(
    /** Tool ids are this, a short form of the server's name and "_", then the tool name. */
    val toolPrefix: String,
    val maxNameChars: Int,
    val icon: String,
    /** Each server signs in back to this plus its id. */
    val redirectBase: String,
    /** Every tool of a server the user added; one the server calls destructive asks every time. */
    val defaultRiskTier: RiskTier,
    /** Model APIs cap tool names; longer ones are left out. */
    val maxToolIdChars: Int,
)

@Serializable
data class McpServerConfig(
    val id: String,
    /** What the user sees: "Supabase". */
    val name: String,
    /** Material Symbols name for the service in Settings. */
    val icon: String,
    val url: String,
    /** Added to [url]: which of the server's tool groups Whittle uses. */
    val query: Map<String, String> = emptyMap(),
    /** Where the sign-in page sends the user back; the app's manifest must route it to Whittle. */
    val redirectUri: String,
    val scopes: List<String>,
    /** Tool ids are this plus the server's tool name, so they never collide with built-in tools. */
    val toolPrefix: String,
    /** Names the source of the server's output, which is untrusted (it can hold anyone's text). */
    val sourceLabel: String,
    /** For tools not listed in [riskTiers], including ones the server adds later. */
    val defaultRiskTier: RiskTier,
    val riskTiers: Map<String, RiskTier> = emptyMap(),
    val auth: McpAuthMode = McpAuthMode.OAUTH,
    val transport: McpTransportKind = McpTransportKind.STREAMABLE_HTTP,
    /** Headers the user is asked for when connecting (an API key, say). */
    val headers: List<HeaderSpec> = emptyList(),
    /** The server's tools the user switched off; the agent never sees them. */
    val excludedTools: Set<String> = emptySet(),
)
