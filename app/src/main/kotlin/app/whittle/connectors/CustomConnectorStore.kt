package app.whittle.connectors

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.whittle.connectors.mcp.HeaderSpec
import app.whittle.connectors.mcp.McpTransportKind
import androidx.datastore.preferences.preferencesDataStoreFile
import app.whittle.connectors.mcp.McpAuthMode
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A server the user added. Its sign-in (tokens, keys) lives in the secret store, never here. */
@Serializable
data class CustomServer(
    val id: String,
    val name: String,
    val url: String,
    val auth: McpAuthMode,
    val transport: McpTransportKind = McpTransportKind.STREAMABLE_HTTP,
    /** Headers the server asks for (named by the library); their values are secrets. */
    val headers: List<HeaderSpec> = emptyList(),
    val websiteUrl: String? = null,
)

/** The MCP servers the user added themselves, in the order they added them, and the tools they switched off. */
class CustomConnectorStore(context: Context, name: String) {
    private val dataStore = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }

    suspend fun load(): List<CustomServer> = dataStore.data.first()[SERVERS]?.let { stored ->
        try {
            json.decodeFromString(LIST, stored)
        } catch (exception: SerializationException) {
            emptyList() // Written by an older version; the user adds them again.
        }
    }.orEmpty()

    suspend fun save(servers: List<CustomServer>) {
        dataStore.edit { it[SERVERS] = json.encodeToString(LIST, servers) }
    }

    /** Tools of server [id] (bundled or added) the user switched off. */
    suspend fun excludedTools(id: String): Set<String> = dataStore.data.first()[excludedKey(id)].orEmpty()

    suspend fun setExcludedTools(id: String, names: Set<String>) {
        dataStore.edit { it[excludedKey(id)] = names }
    }

    private fun excludedKey(id: String) = stringSetPreferencesKey(EXCLUDED_PREFIX + id)

    private companion object {
        val SERVERS = stringPreferencesKey("servers")
        const val EXCLUDED_PREFIX = "excluded_tools:"
        val LIST = ListSerializer(CustomServer.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
