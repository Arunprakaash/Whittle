package app.whittle.linux

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What `tailscale status --json` says: how the daemon stands, the sign-in link while it waits, and the phone on the tailnet. */
data class TailscaleStatus(val backendState: String, val authUrl: String?, val name: String?, val ipv4: String?) {
    val running: Boolean get() = backendState == RUNNING
    val needsLogin: Boolean get() = backendState == NEEDS_LOGIN

    companion object {
        private const val RUNNING = "Running"
        private const val NEEDS_LOGIN = "NeedsLogin"
        private val json = Json { ignoreUnknownKeys = true }

        /** Null when [output] isn't the status JSON (the daemon isn't up yet, say). */
        fun parse(output: String): TailscaleStatus? {
            val root = try {
                json.parseToJsonElement(output.trim()) as? JsonObject
            } catch (exception: SerializationException) {
                null
            } ?: return null
            val state = root.text("BackendState") ?: return null
            val self = root["Self"] as? JsonObject
            val ipv4 = (self?.get("TailscaleIPs") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.firstOrNull { '.' in it }
            return TailscaleStatus(state, root.text("AuthURL"), self?.text("HostName"), ipv4)
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}
