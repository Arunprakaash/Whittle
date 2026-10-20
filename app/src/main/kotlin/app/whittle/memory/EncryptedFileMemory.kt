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

/** The phone's memory: one file, encrypted with the app's Keystore-backed key, written whole and swapped in. */
class EncryptedFileMemory(private val file: File, private val cipher: ContentCipher) : MemoryBackend {
    override suspend fun load(): List<Memory> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        val bytes = try {
            file.readBytes()
        } catch (exception: IOException) {
            return@withContext emptyList()
        }
        val plain = (cipher.decrypt(bytes, ASSOCIATED_DATA) as? Outcome.Success)?.value ?: return@withContext emptyList()
        try {
            json.decodeFromString(LIST, plain.decodeToString())
        } catch (exception: SerializationException) {
            emptyList()
        }
    }

    override suspend fun save(memories: List<Memory>): Outcome<Unit> = withContext(Dispatchers.IO) {
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

    private companion object {
        val ASSOCIATED_DATA = "whittle-memory".encodeToByteArray()
        val LIST = ListSerializer(Memory.serializer())
        val json = Json { ignoreUnknownKeys = true }
        const val PARTIAL = ".part"
    }
}
