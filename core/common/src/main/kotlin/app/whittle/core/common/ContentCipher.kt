package app.whittle.core.common

/** Authenticated encryption for data at rest. [associatedData] binds a ciphertext to its record. */
interface ContentCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Outcome<ByteArray>

    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): Outcome<ByteArray>
}
