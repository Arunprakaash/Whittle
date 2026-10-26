package app.whittle.connections.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The services the user connects, from wherever they're shown: the phone's own, or the same over the link. */
interface ConnectionsHost {
    val state: StateFlow<ConnectionsState>

    /** Why connecting a service failed, as it happens, in the service's words. */
    val failures: Flow<String>

    fun act(action: ConnectionAction)

    /** Adds a server from the library, by its address. */
    suspend fun addFromLibrary(url: String): ConnectionAdd

    /** Adds any server by its address; [keys] connect it at once when it signs in with them. */
    suspend fun add(connection: NewConnection, keys: Map<String, String>): ConnectionAdd

    /** Where a connection named [name] would have its sign-in return, to register with a service before adding it. */
    suspend fun returnAddressFor(name: String): String
}

@Serializable
data class ConnectionsState(
    val connections: List<ConnectionEntry> = emptyList(),
    val categories: List<LibraryCategoryEntry> = emptyList(),
    val library: List<LibraryEntry> = emptyList(),
)

/** One connection: what it is, how it signs in, how it stands, and its tools. */
@Serializable
data class ConnectionEntry(
    val id: String,
    val name: String,
    val icon: String,
    /** Where its logo comes from. */
    val logoHost: String? = null,
    val url: String,
    val websiteUrl: String? = null,
    val description: String? = null,
    val category: String? = null,
    /** The user added it, as opposed to one Whittle comes with. */
    val custom: Boolean,
    val auth: ConnectionAuth,
    val status: ConnectionStatus,
    /** False while a connected server doesn't answer; its tools stay, from the cached list. */
    val reachable: Boolean = true,
    /** Every tool it offers, each on or off. */
    val tools: List<ConnectionTool> = emptyList(),
    /** The keys it asks for when it signs in with them. */
    val keys: List<KeyField> = emptyList(),
)

@Serializable
enum class ConnectionAuth { Service, Key, None }

@Serializable
enum class ConnectionStatus { Disconnected, Connecting, Connected, SignInExpired }

@Serializable
data class ConnectionTool(val name: String, val description: String, val on: Boolean)

@Serializable
data class KeyField(val name: String, val description: String? = null, val required: Boolean = false, val secret: Boolean = false)

@Serializable
data class LibraryCategoryEntry(val id: String, val title: String)

/** A server the library can add, with the words people look for it by. */
@Serializable
data class LibraryEntry(
    val url: String,
    val title: String,
    val description: String,
    val websiteUrl: String? = null,
    val category: String? = null,
    val keywords: List<String> = emptyList(),
    /** Already added. */
    val added: Boolean = false,
)

/** A server the user adds by its address; null [auth] and [transport] let Whittle check the server. */
@Serializable
data class NewConnection(
    val name: String,
    val url: String,
    val auth: ConnectionAuth? = null,
    val transport: ConnectionTransport? = null,
    /** Headers it signs in with, by name. */
    val keyNames: List<String> = emptyList(),
    val scopes: List<String> = emptyList(),
    /** A client the user registered with the service themselves. */
    val clientId: String? = null,
    val clientSecret: String? = null,
)

@Serializable
enum class ConnectionTransport { StreamableHttp, Sse }

@Serializable
sealed interface ConnectionAdd {
    /** Added; [id] is the new connection, which may still need signing in. */
    @Serializable
    @SerialName("added")
    data class Added(val id: String) : ConnectionAdd

    /** Not added, and why; null when a computer's phone didn't answer. */
    @Serializable
    @SerialName("failed")
    data class Failed(val message: String? = null) : ConnectionAdd
}

@Serializable
sealed interface ConnectionAction {
    /** Starts its sign-in on the service's own page, which happens on the phone. */
    @Serializable
    @SerialName("connect")
    data class Connect(val id: String) : ConnectionAction

    @Serializable
    @SerialName("connect_keys")
    data class ConnectWithKeys(val id: String, val values: Map<String, String>) : ConnectionAction

    @Serializable
    @SerialName("rename")
    data class Rename(val id: String, val name: String) : ConnectionAction

    /** Forgets a connection the user added. */
    @Serializable
    @SerialName("remove")
    data class Remove(val id: String) : ConnectionAction

    /** Signs out of one Whittle comes with; it goes back to the library. */
    @Serializable
    @SerialName("disconnect")
    data class Disconnect(val id: String) : ConnectionAction

    @Serializable
    @SerialName("tool")
    data class SetTool(val id: String, val tool: String, val on: Boolean) : ConnectionAction
}

/** What only the device does for Connections: a service's own sign-in page (the phone's), and short messages. */
class ConnectionsPlatform(
    /** The device can sign in on a service's page; elsewhere such a sign-in is done on the phone. */
    val signsInHere: Boolean,
    val toast: (String) -> Unit,
)

/** Whether [query] names this server, what it does, or something people look for it by. */
fun LibraryEntry.matches(query: String): Boolean {
    val words = query.trim().lowercase()
    return words.isEmpty() || (listOf(title, description) + keywords).any { words in it.lowercase() }
}

/** The same for a connection Whittle comes with, by its name and what it does. */
fun ConnectionEntry.matches(query: String): Boolean {
    val words = query.trim().lowercase()
    return words.isEmpty() || listOfNotNull(name, description).any { words in it.lowercase() }
}
