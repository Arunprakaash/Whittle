package app.whittle.companion

/**
 * The way to the phone that a computer opens by itself (the Mac app does, with the computer's own ssh): it reads the
 * token from the phone and keeps a tunnel to the link open. A browser has none, and its page shows the steps instead.
 */
interface PhoneTunnel {
    /** This computer's public key, as the phone's Add a computer takes it; null when it has none. */
    val publicKey: String?

    /** What makes one in a terminal, when it has none. */
    val makeKeyCommand: String

    /** The phone it reached last, to reach again at start; null before it ever has. */
    val lastHost: String?

    /** Reads the token from the phone at [host] and opens the tunnel; what to connect to, or why it couldn't. */
    suspend fun open(host: String): TunnelResult

    /** Closes the tunnel, as the app quits. */
    fun close()
}

sealed interface TunnelResult {
    /** Open: the link's address on this computer, and the phone's token. */
    data class Opened(val address: String, val token: String) : TunnelResult

    /** The phone didn't let this computer in: its key isn't added there. */
    data object NotAdded : TunnelResult

    /** Nothing answered at that address. */
    data object Unreachable : TunnelResult

    /** Something answered as the phone with a different host key than this computer knew. */
    data object HostChanged : TunnelResult

    /** This computer has no ssh to open it with. */
    data object NoSsh : TunnelResult

    /** Signed in, but the tunnel or the token didn't come; [detail] is what ssh said. */
    data class Failed(val detail: String?) : TunnelResult
}
