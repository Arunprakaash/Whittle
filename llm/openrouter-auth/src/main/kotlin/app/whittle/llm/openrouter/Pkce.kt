package app.whittle.llm.openrouter

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE helpers (RFC 7636) using base64url without padding. */
class Pkce(private val random: SecureRandom = SecureRandom()) {
    fun randomToken(entropyBytes: Int): String {
        val bytes = ByteArray(entropyBytes).also(random::nextBytes)
        return encoder.encodeToString(bytes)
    }

    fun challengeFor(verifier: String, method: CodeChallengeMethod): String = when (method) {
        CodeChallengeMethod.S256 -> encoder.encodeToString(
            MessageDigest.getInstance(SHA_256).digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
