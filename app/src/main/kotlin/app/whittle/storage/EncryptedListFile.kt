package app.whittle.storage

import app.whittle.core.common.ContentCipher
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** A list kept whole: read all of it, write all of it. */
interface ListStore<T> {
    suspend fun load(): List<T>

    suspend fun save(items: List<T>): Outcome<Unit>
}

/**
 * A list kept in one file, encrypted with the app's Keystore-backed key, written whole and swapped in. A file that can't
 * be read (a lost key, or entries that no longer pass their checks) is set aside as [UNREADABLE] rather than left for the
 * next save to overwrite. [label] names what's kept, for errors; [associatedData] ties the file to its use.
 */
class EncryptedListFile<T>(
    private val file: File,
    private val cipher: ContentCipher,
    serializer: KSerializer<T>,
    private val associatedData: ByteArray,
    private val label: String,
) : ListStore<T> {
    private val list = ListSerializer(serializer)

    override suspend fun load(): List<T> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        val bytes = try {
            file.readBytes()
        } catch (exception: IOException) {
            return@withContext setAside()
        }
        val plain = (cipher.decrypt(bytes, associatedData) as? Outcome.Success)?.value ?: return@withContext setAside()
        try {
            json.decodeFromString(list, plain.decodeToString())
        } catch (exception: SerializationException) {
            setAside()
        } catch (exception: IllegalArgumentException) {
            setAside()
        }
    }

    /** Keeps the unreadable file next to the new one, replacing any kept before. */
    private fun setAside(): List<T> {
        val aside = File(file.parentFile, file.name + UNREADABLE)
        aside.delete()
        file.renameTo(aside)
        return emptyList()
    }

    override suspend fun save(items: List<T>): Outcome<Unit> = withContext(Dispatchers.IO) {
        when (val sealed = cipher.encrypt(json.encodeToString(list, items).encodeToByteArray(), associatedData)) {
            is Outcome.Failure -> sealed
            is Outcome.Success -> try {
                val partial = File(file.parentFile, file.name + PARTIAL)
                partial.writeBytes(sealed.value)
                if (!partial.renameTo(file)) throw IOException("rename failed")
                Outcome.Success(Unit)
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Storage("Couldn't save $label: ${exception.message}"))
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val PARTIAL = ".part"
        const val UNREADABLE = ".unreadable"
    }
}
