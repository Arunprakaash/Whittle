package app.whittle.automations

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
 * The user's automations in one file, encrypted with the app's Keystore-backed key, written whole and swapped in.
 * A file that can't be read (a lost key, or a schedule that no longer passes its checks) is set aside as
 * [UNREADABLE] rather than left for the next save to overwrite.
 */
class EncryptedFileAutomations(private val file: File, private val cipher: ContentCipher) : AutomationBackend {
    override suspend fun load(): List<Automation> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        val bytes = try {
            file.readBytes()
        } catch (exception: IOException) {
            return@withContext setAside()
        }
        val plain = (cipher.decrypt(bytes, ASSOCIATED_DATA) as? Outcome.Success)?.value ?: return@withContext setAside()
        try {
            json.decodeFromString(LIST, plain.decodeToString())
        } catch (exception: SerializationException) {
            setAside()
        } catch (exception: IllegalArgumentException) {
            setAside()
        }
    }

    /** Keeps the unreadable file next to the new one, replacing any kept before. */
    private fun setAside(): List<Automation> {
        val aside = File(file.parentFile, file.name + UNREADABLE)
        aside.delete()
        file.renameTo(aside)
        return emptyList()
    }

    override suspend fun save(automations: List<Automation>): Outcome<Unit> = withContext(Dispatchers.IO) {
        when (val sealed = cipher.encrypt(json.encodeToString(LIST, automations).encodeToByteArray(), ASSOCIATED_DATA)) {
            is Outcome.Failure -> sealed
            is Outcome.Success -> try {
                val partial = File(file.parentFile, file.name + PARTIAL)
                partial.writeBytes(sealed.value)
                if (!partial.renameTo(file)) throw IOException("rename failed")
                Outcome.Success(Unit)
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Storage("Couldn't save automations: ${exception.message}"))
            }
        }
    }

    private companion object {
        val ASSOCIATED_DATA = "whittle-automations".encodeToByteArray()
        val LIST = ListSerializer(Automation.serializer())
        val json = Json { ignoreUnknownKeys = true }
        const val PARTIAL = ".part"
        const val UNREADABLE = ".unreadable"
    }
}
