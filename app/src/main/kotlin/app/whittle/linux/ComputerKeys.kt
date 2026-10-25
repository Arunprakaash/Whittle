package app.whittle.linux

import java.security.MessageDigest
import java.util.Base64

/**
 * A computer allowed to sign in: its public key as `ssh-keygen` writes it. [comment] is what the key calls itself,
 * usually `user@host`, and names the computer in Settings.
 */
data class ComputerKey(val type: String, val blob: String, val comment: String) {
    /** The line the SSH server reads. */
    val line: String get() = listOf(type, blob, comment).filter { it.isNotEmpty() }.joinToString(" ")

    /** The fingerprint `ssh-keygen -l` shows: SHA256 and the key's digest, unpadded. */
    val fingerprint: String
        get() = SHA256_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance(SHA_256).digest(Base64.getDecoder().decode(blob)))

    /** The same key, whatever it's called. */
    fun sameKey(other: ComputerKey): Boolean = type == other.type && blob == other.blob

    private companion object {
        const val SHA_256 = "SHA-256"
        const val SHA256_PREFIX = "SHA256:"
    }
}

/** Why a pasted key wasn't added. */
enum class KeyProblem { NOT_A_KEY, TYPE_NOT_ALLOWED, ALREADY_ADDED, TOO_MANY }

/** Reading and changing the keys file; pure, so it's tested on the JVM. */
object ComputerKeys {
    /** The keys in a keys file, skipping blank lines, comments and anything that isn't a key. */
    fun parse(text: String): List<ComputerKey> = text.lines().mapNotNull { line -> parseLine(line) }

    /** The keys file for [keys], one per line. */
    fun write(keys: List<ComputerKey>): String = keys.joinToString("") { it.line + "\n" }

    /**
     * [keys] with [pasted] added: one public key line, of a type in [allowedTypes], not there already, and at most
     * [max] in all. Anything around the key, like a trailing newline, is trimmed.
     */
    fun add(keys: List<ComputerKey>, pasted: String, allowedTypes: List<String>, max: Int): Result {
        val key = pasted.trim().takeIf { it.lines().size == 1 }?.let(::parseLine) ?: return Result.Refused(KeyProblem.NOT_A_KEY)
        return when {
            key.type !in allowedTypes -> Result.Refused(KeyProblem.TYPE_NOT_ALLOWED)
            keys.any { it.sameKey(key) } -> Result.Refused(KeyProblem.ALREADY_ADDED)
            keys.size >= max -> Result.Refused(KeyProblem.TOO_MANY)
            else -> Result.Added(keys + key, key)
        }
    }

    /** How adding went: the new list with the key, or why not. */
    sealed interface Result {
        data class Added(val keys: List<ComputerKey>, val key: ComputerKey) : Result

        data class Refused(val problem: KeyProblem) : Result
    }

    /**
     * A key line: its type, then its blob, which must decode and name the same type inside (as every SSH public key
     * does), then an optional comment. Lines with options in front, as authorized_keys allows, aren't accepted.
     */
    private fun parseLine(line: String): ComputerKey? {
        val parts = line.trim().split(WHITESPACE, limit = PARTS)
        if (parts.size < 2 || parts[0].startsWith(COMMENT)) return null
        val (type, blob) = parts
        val decoded = try {
            Base64.getDecoder().decode(blob)
        } catch (exception: IllegalArgumentException) {
            return null
        }
        if (namedType(decoded) != type) return null
        return ComputerKey(type, blob, parts.getOrNull(2)?.trim().orEmpty())
    }

    /** The type a key blob starts with: a 4-byte big-endian length, then that many bytes of ASCII. */
    private fun namedType(blob: ByteArray): String? {
        if (blob.size < LENGTH_BYTES) return null
        val length = (0 until LENGTH_BYTES).fold(0) { value, i -> (value shl BITS) or (blob[i].toInt() and BYTE_MASK) }
        if (length <= 0 || length > blob.size - LENGTH_BYTES) return null
        return String(blob, LENGTH_BYTES, length, Charsets.US_ASCII)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val PARTS = 3
    private const val COMMENT = "#"
    private const val LENGTH_BYTES = 4
    private const val BITS = 8
    private const val BYTE_MASK = 0xFF
}
