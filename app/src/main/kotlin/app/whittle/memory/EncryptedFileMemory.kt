package app.whittle.memory

import app.whittle.agent.Memory
import app.whittle.agent.MemoryBackend
import app.whittle.core.common.ContentCipher
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The phone's memory: one file, encrypted with the app's Keystore-backed key, written whole and swapped in. A file
 * that can't be read (a key that changed, a damaged write) loads as empty memory, but it's never overwritten: the
 * next save moves it aside first, so what it held can still be recovered.
 */
class EncryptedFileMemory(private val file: File, private val cipher: ContentCipher) : MemoryBackend {
    /** The file on the phone couldn't be read at the last load. */
    @Volatile
    private var unreadable = false

    override suspend fun load(): List<Memory> = withContext(Dispatchers.IO) {
        unreadable = false
        if (!file.isFile) return@withContext emptyList()
        read() ?: emptyList<Memory>().also { unreadable = true }
    }

    override suspend fun save(memories: List<Memory>): Outcome<Unit> = withContext(Dispatchers.IO) {
        if (unreadable && file.isFile) {
            if (!file.renameTo(File(file.parentFile, file.name + UNREADABLE))) {
                return@withContext Outcome.Failure(HarnessError.Storage("Couldn't keep aside the memory file that couldn't be read"))
            }
            unreadable = false
        }
        when (val sealed = cipher.encrypt(json.encodeToString(LIST, memories).encodeToByteArray(), ASSOCIATED_DATA)) {
            is Outcome.Failure -> sealed
            is Outcome.Success -> try {
                val partial = File(file.parentFile, file.name + PARTIAL)
                partial.writeBytes(sealed.value)
                if (!partial.renameTo(file)) throw IOException("rename failed")
                Outcome.Success(Unit)
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Storage("Couldn't save what Whittle remembers: ${exception.message}"))
            }
        }
    }

    /** What the file holds, or null when it can't be read, decrypted or parsed. */
    private fun read(): List<Memory>? {
        val bytes = try {
            file.readBytes()
        } catch (exception: IOException) {
            return null
        }
        val plain = (cipher.decrypt(bytes, ASSOCIATED_DATA) as? Outcome.Success)?.value ?: return null
        return try {
            json.decodeFromString(LIST, plain.decodeToString())
        } catch (exception: SerializationException) {
            null
        }
    }

    private companion object {
        val ASSOCIATED_DATA = "whittle-memory".encodeToByteArray()
        /** Where a file that couldn't be read is kept; a later one replaces it. */
        const val UNREADABLE = ".unreadable"
        val LIST = ListSerializer(Memory.serializer())
        val json = Json { ignoreUnknownKeys = true }
        const val PARTIAL = ".part"
    }
}
