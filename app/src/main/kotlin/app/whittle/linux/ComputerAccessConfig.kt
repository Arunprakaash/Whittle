package app.whittle.linux

import app.whittle.companion.CompanionConfig
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
    /** Runs the server in the foreground; takes `{{port}}`, the `{{listen}}` options for its addresses and the `{{keysFile}}`. */
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
    /** The phone's chats on a computer that signed in, over the same SSH. */
    val companion: CompanionConfig,
    /** Reaching the phone from anywhere, through Tailscale run inside Linux. */
    val tailscale: TailscaleConfig,
) : VersionedConfig {
    init {
        require(port in MIN_PORT..MAX_PORT) { "port must be between $MIN_PORT and $MAX_PORT" }
        require(user.isNotBlank() && keysFile.startsWith("/")) { "user must be set and keysFile an absolute Linux path" }
        require(PromptTemplate(FILE, serveCommand).placeholders == setOf(PORT, LISTEN, KEYS_FILE)) {
            "serveCommand must take {{$PORT}}, {{$LISTEN}} and {{$KEYS_FILE}} and nothing else"
        }
        require(installTimeoutSeconds > 0 && startWaitSeconds > 0 && keptOutputChars > 0 && maxComputers > 0) { "limits must be positive" }
        require(keyTypes.isNotEmpty()) { "keyTypes must name at least one key type" }
    }

    /**
     * The server command, listening on the phone itself and on [wifi] when there is one. Tailscale needs no address of
     * its own: it hands what arrives over the tailnet to the phone's loopback.
     */
    fun serve(wifi: String?): String {
        val listen = listOfNotNull(LOOPBACK, wifi).joinToString(" ") { "-o ListenAddress=$it" }
        return serveCommand.replace("{{$PORT}}", port.toString()).replace("{{$LISTEN}}", listen).replace("{{$KEYS_FILE}}", keysFile)
    }

    /** What a computer types to sign in at [address]. */
    fun signIn(address: String): String = "ssh -p $port $user@$address"

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("computer-access.json")
        private const val PORT = "port"
        private const val LISTEN = "listen"
        private const val LOOPBACK = "127.0.0.1"
        private const val KEYS_FILE = "keysFile"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
    }
}

/**
 * Tailscale inside Linux, in userspace networking: no VPN and no root, and it hands connections from the user's tailnet
 * to the phone's loopback. [cli] reaches the daemon [daemonCommand] starts; [signInArguments] takes `{{hostname}}`, the
 * phone's name on the tailnet, [hostnamePrefix] and its model. [markerFile] records the last sign-in, so Settings knows
 * it while the daemon is off.
 */
@Serializable
data class TailscaleConfig(
    val checkCommand: String,
    val installCommand: String,
    val removeCommand: String,
    val installTimeoutSeconds: Int,
    val daemonCommand: String,
    val cli: String,
    val statusArguments: String,
    val signInArguments: String,
    val signOutArguments: String,
    val commandTimeoutSeconds: Int,
    val markerFile: String,
    val hostnamePrefix: String,
    /** How long a start waits for the tailnet before it counts as not connected. */
    val connectWaitSeconds: Int,
    /** How often the daemon is asked how it stands while signing in or connecting. */
    val pollMillis: Long,
    /** And while connected, to notice a sign-out or an expired key. */
    val connectedPollSeconds: Int,
    val keptOutputChars: Int,
    /** How much of the status JSON is read; it lists every device on the tailnet unless asked not to. */
    val statusMaxChars: Int,
) {
    init {
        require(statusMaxChars > 0) { "tailscale.statusMaxChars must be positive" }
        require(PromptTemplate(ComputerAccessConfig.FILE, signInArguments).placeholders == setOf(HOSTNAME)) { "tailscale.signInArguments must take {{$HOSTNAME}}" }
        require(markerFile.startsWith("/")) { "tailscale.markerFile must be an absolute Linux path" }
        require(installTimeoutSeconds > 0 && commandTimeoutSeconds > 0 && connectWaitSeconds > 0 && pollMillis > 0 && connectedPollSeconds > 0 && keptOutputChars > 0) {
            "tailscale limits must be positive"
        }
    }

    fun status(): String = "$cli $statusArguments"

    fun signIn(hostname: String): String = "$cli " + signInArguments.replace("{{$HOSTNAME}}", hostname)

    fun signOut(): String = "$cli $signOutArguments"

    /** The phone's name on the tailnet: [hostnamePrefix] and [model], as a DNS label ("whittle-pixel-9"). */
    fun hostname(model: String): String =
        (hostnamePrefix + model).lowercase().replace(NOT_LABEL, "-").replace(DASHES, "-").trim('-').take(MAX_LABEL)

    private companion object {
        const val HOSTNAME = "hostname"
        const val MAX_LABEL = 63
        val NOT_LABEL = Regex("[^a-z0-9-]")
        val DASHES = Regex("-+")
    }
}
