package app.whittle.linux

import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable

/**
 * Using the Linux workspace from a computer on the same Wi-Fi, over SSH (computer-access.json). OpenSSH's server is
 * installed in Linux the first time, then runs while the switch is on, signing in only the computers the user added.
 */
@Serializable
data class ComputerAccessConfig(
    override val schemaVersion: Int,
    val port: Int,
    /** Who a computer signs in as; Linux's own user. */
    val user: String,
    /** Exits with 0 when the SSH server is installed. */
    val checkCommand: String,
    /** Run as root in Linux, with the network; installs the server without recommended extras. */
    val installCommand: String,
    val installTimeoutSeconds: Int,
    /** The Linux path of the keys of the computers the user added, one per line, which the server reads. */
    val keysFile: String,
    /** Runs the server in the foreground; takes `{{port}}`, the Wi-Fi `{{address}}` and the `{{keysFile}}`. */
    val serveCommand: String,
    /** How long the server has to answer before turning on counts as failed. */
    val startWaitSeconds: Int,
    /** The server's latest output kept, to say why it stopped. */
    val keptOutputChars: Int,
    /** The key types a computer may add, as their public-key lines start. */
    val keyTypes: List<String>,
    val maxComputers: Int,
    /** Shown in Add a computer: prints the computer's public key, and makes one when there's none. */
    val printKeyCommand: String,
    val makeKeyCommand: String,
) : VersionedConfig {
    init {
        require(port in MIN_PORT..MAX_PORT) { "port must be between $MIN_PORT and $MAX_PORT" }
        require(user.isNotBlank() && keysFile.startsWith("/")) { "user must be set and keysFile an absolute Linux path" }
        require(PromptTemplate(FILE, serveCommand).placeholders == setOf(PORT, ADDRESS, KEYS_FILE)) {
            "serveCommand must take {{$PORT}}, {{$ADDRESS}} and {{$KEYS_FILE}} and nothing else"
        }
        require(installTimeoutSeconds > 0 && startWaitSeconds > 0 && keptOutputChars > 0 && maxComputers > 0) { "limits must be positive" }
        require(keyTypes.isNotEmpty()) { "keyTypes must name at least one key type" }
    }

    /** The server command for [address], with this config's port and keys file. */
    fun serve(address: String): String =
        serveCommand.replace("{{$PORT}}", port.toString()).replace("{{$ADDRESS}}", address).replace("{{$KEYS_FILE}}", keysFile)

    /** What a computer types to sign in at [address]. */
    fun signIn(address: String): String = "ssh -p $port $user@$address"

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("computer-access.json")
        private const val PORT = "port"
        private const val ADDRESS = "address"
        private const val KEYS_FILE = "keysFile"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
    }
}
