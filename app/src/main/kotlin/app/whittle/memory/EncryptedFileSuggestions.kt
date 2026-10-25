package app.whittle.memory

import app.whittle.core.common.ContentCipher
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The phone's memory suggestions: one file, encrypted with the app's Keystore-backed key, written whole and swapped in. A file
 * that can't be read (a key that changed, a damaged write) loads as empty, but it's never overwritten: the
 * next save moves it aside first, so what it held can still be recovered.
 */
class EncryptedFileSuggestions(private val file: File, private val cipher: ContentCipher) : SuggestionsBackend {
    /** The file on the phone couldn't be read at the last load. */
    @Volatile
    private var unreadable = false

    override suspend fun load(): SuggestionsState = withContext(Dispatchers.IO) {
        unreadable = false
        if (!file.isFile) return@withContext EMPTY
        read() ?: EMPTY.also { unreadable = true }
    }

    override suspend fun save(state: SuggestionsState): Outcome<Unit> = withContext(Dispatchers.IO) {
        if (unreadable && file.isFile) {
            if (!file.renameTo(File(file.parentFile, file.name + UNREADABLE))) {
                return@withContext Outcome.Failure(HarnessError.Storage("Couldn't keep aside the suggestions file that couldn't be read"))
            }
            unreadable = false
        }
        when (val sealed = cipher.encrypt(json.encodeToString(SuggestionsState.serializer(), state).encodeToByteArray(), ASSOCIATED_DATA)) {
            is Outcome.Failure -> sealed
            is Outcome.Success -> try {
                val partial = File(file.parentFile, file.name + PARTIAL)
                partial.writeBytes(sealed.value)
                if (!partial.renameTo(file)) throw IOException("rename failed")
                Outcome.Success(Unit)
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Storage("Couldn't save the memory suggestions: ${exception.message}"))
            }
        }
    }

    /** What the file holds, or null when it can't be read, decrypted or parsed. */
    private fun read(): SuggestionsState? {
        val bytes = try {
            file.readBytes()
        } catch (exception: IOException) {
            return null
        }
        val plain = (cipher.decrypt(bytes, ASSOCIATED_DATA) as? Outcome.Success)?.value ?: return null
        return try {
            json.decodeFromString(SuggestionsState.serializer(), plain.decodeToString())
        } catch (exception: SerializationException) {
            null
        }
    }

    private companion object {
        val ASSOCIATED_DATA = "whittle-memory-suggestions".encodeToByteArray()
        /** Where a file that couldn't be read is kept; a later one replaces it. */
        const val UNREADABLE = ".unreadable"
        val EMPTY = SuggestionsState(emptyList(), emptyList(), emptyMap())
        val json = Json { ignoreUnknownKeys = true }
        const val PARTIAL = ".part"
    }
}
