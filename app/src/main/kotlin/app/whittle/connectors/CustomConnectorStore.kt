package app.whittle.connectors

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.whittle.connectors.mcp.McpAuthMode
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A server the user added. Its sign-in (tokens) lives in the secret store, never here. */
@Serializable
data class CustomServer(val id: String, val name: String, val url: String, val auth: McpAuthMode)

/** The MCP servers the user added themselves, in the order they added them. */
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

    private companion object {
        val SERVERS = stringPreferencesKey("servers")
        val LIST = ListSerializer(CustomServer.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
