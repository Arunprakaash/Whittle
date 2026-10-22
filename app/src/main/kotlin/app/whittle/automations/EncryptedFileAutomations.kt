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

/** The user's automations in one file, encrypted with the app's Keystore-backed key, written whole and swapped in. */
class EncryptedFileAutomations(private val file: File, private val cipher: ContentCipher) : AutomationBackend {
    override suspend fun load(): List<Automation> = withContext(Dispatchers.IO) {
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
        } catch (exception: IllegalArgumentException) {
            emptyList() // A schedule that no longer passes its checks.
        }
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
    }
}
