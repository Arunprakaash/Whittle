package app.whittle.companion

import kotlinx.serialization.Serializable

/**
 * The phone's chats on the user's computer (computer-access.json, `companion`). The phone serves them on its own loopback
 * at [port] only, so a computer reaches them through its SSH sign-in (`ssh -L`), and only with the token in [tokenFile],
 * a Linux path that only signed-in computers can read. Views go out at most every [updateMillis] while a reply streams.
 */
@Serializable
data class CompanionConfig(
    val port: Int,
    val tokenFile: String,
    val tokenBytes: Int,
    /** How long a computer has to say hello with the token before it's let go. */
    val helloTimeoutSeconds: Int,
    val updateMillis: Long,
) {
    init {
        require(port in MIN_PORT..MAX_PORT) { "companion port must be between $MIN_PORT and $MAX_PORT" }
        require(tokenFile.startsWith("/")) { "companion tokenFile must be an absolute Linux path" }
        require(tokenBytes >= MIN_TOKEN_BYTES && helloTimeoutSeconds > 0 && updateMillis > 0) { "companion limits must be positive" }
    }

    private companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MIN_TOKEN_BYTES = 16
    }
}
