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
)
