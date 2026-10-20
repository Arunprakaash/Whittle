package app.whittle.memory

import app.whittle.core.common.ContentCipher
import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.common.map
import app.whittle.settings.MemoryDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** One thing Whittle remembers about the user. */
@Serializable
data class Memory(val id: String, val text: String, val savedAtMillis: Long)

/**
 * What Whittle remembers about the user across chats, newest last. Kept in one encrypted file on the phone;
 * nothing here leaves it except as part of the system prompt sent to the user's own provider.
 */
class MemoryStore(
    private val file: File,
    private val cipher: ContentCipher,
    private val clock: EpochClock,
    private val ids: IdGenerator,
    private val config: MemoryDefaults,
    scope: CoroutineScope,
) {
    private val changes = Mutex()
    private val _all = MutableStateFlow<List<Memory>>(emptyList())
    private val loaded = scope.async(Dispatchers.IO) { read().also { _all.value = it } }

    /** Everything remembered, as it is now; empty until the file has loaded at start-up. */
    val all: StateFlow<List<Memory>> = _all.asStateFlow()

    init {
        scope.launch { loaded.await() }
    }

    suspend fun remember(text: String): Outcome<Memory> = changes.withLock {
        val fact = text.trim().replace(WHITESPACE, " ")
        if (fact.isEmpty()) return@withLock Outcome.Failure(HarnessError.Configuration("There's nothing to remember."))
        if (fact.length > config.maxItemChars) {
            return@withLock Outcome.Failure(HarnessError.Configuration("Keep it under ${config.maxItemChars} characters: one short fact."))
        }
        loaded.await()
        val current = _all.value
        current.firstOrNull { it.text.equals(fact, ignoreCase = true) }?.let { return@withLock Outcome.Success(it) }
        if (current.size >= config.maxItems) {
            return@withLock Outcome.Failure(HarnessError.Configuration("Memory is full (${config.maxItems} facts). Forget one that no longer matters first."))
        }
        val memory = Memory(ids.newId().take(ID_CHARS), fact, clock.nowMillis())
        save(current + memory).map { memory }
    }

    /** False when nothing had that id. */
    suspend fun forget(id: String): Outcome<Boolean> = changes.withLock {
        loaded.await()
        val current = _all.value
        val kept = current.filterNot { it.id == id }
        if (kept.size == current.size) Outcome.Success(false) else save(kept).map { true }
    }

    suspend fun clear(): Outcome<Unit> = changes.withLock {
        loaded.await()
        save(emptyList())
    }

    private suspend fun save(memories: List<Memory>): Outcome<Unit> = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(LIST, memories).encodeToByteArray()
        when (val sealed = cipher.encrypt(bytes, ASSOCIATED_DATA)) {
            is Outcome.Failure -> sealed
            is Outcome.Success -> try {
                val partial = File(file.parentFile, file.name + PARTIAL)
                partial.writeBytes(sealed.value)
                if (!partial.renameTo(file)) throw IOException("rename failed")
                _all.value = memories
                Outcome.Success(Unit)
            } catch (exception: IOException) {
                Outcome.Failure(HarnessError.Storage("Couldn't save what Whittle remembers: ${exception.message}"))
            }
        }
    }

    private fun read(): List<Memory> {
        if (!file.isFile) return emptyList()
        val bytes = try {
            file.readBytes()
        } catch (exception: IOException) {
            return emptyList()
        }
        val plain = (cipher.decrypt(bytes, ASSOCIATED_DATA) as? Outcome.Success)?.value ?: return emptyList()
        return try {
            json.decodeFromString(LIST, plain.decodeToString())
        } catch (exception: SerializationException) {
            emptyList()
        }
    }

    private companion object {
        val ASSOCIATED_DATA = "whittle-memory".encodeToByteArray()
        val LIST = ListSerializer(Memory.serializer())
        val json = Json { ignoreUnknownKeys = true }
        val WHITESPACE = Regex("""\s+""")
        const val PARTIAL = ".part"

        /** Short ids are enough for a few dozen facts and cheap in the prompt. */
        const val ID_CHARS = 8
    }
}
