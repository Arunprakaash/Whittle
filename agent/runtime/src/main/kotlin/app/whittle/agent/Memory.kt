package app.whittle.agent

import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.common.map
import app.whittle.core.config.MemoryPolicy
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** One thing the agent remembers about the user. */
@Serializable
data class Memory(val id: String, val text: String, val savedAtMillis: Long)

/** Where remembered facts are kept; each host picks its own (an encrypted file on the phone, a plain one on a desktop). */
interface MemoryBackend {
    /** Everything saved so far, oldest first; empty when nothing is, or what's there can't be read. */
    suspend fun load(): List<Memory>

    suspend fun save(memories: List<Memory>): Outcome<Unit>
}

/**
 * What the agent remembers about the user across chats. The runner puts these facts in every task's system
 * prompt, the remember and forget tools change them, and the user can review them in the host's own screen.
 * Limits come from [MemoryPolicy] and what the model is told from [AgentMessages].
 */
class MemoryStore(
    private val backend: MemoryBackend,
    /** The limits, which the host's screen shows too. */
    val policy: MemoryPolicy,
    private val messages: AgentMessages,
    private val clock: EpochClock,
    private val ids: IdGenerator,
    scope: CoroutineScope,
) {
    private val changes = Mutex()
    private val _all = MutableStateFlow<List<Memory>>(emptyList())
    private val loaded = scope.async { backend.load().also { _all.value = it } }

    /** Everything remembered, as it is now; empty until the backend has loaded. */
    val all: StateFlow<List<Memory>> = _all.asStateFlow()

    /** Everything remembered, once loaded: what a new task's system prompt shows. */
    suspend fun current(): List<Memory> {
        loaded.await()
        return _all.value
    }

    suspend fun remember(text: String): Outcome<Memory> = changes.withLock {
        val fact = when (val checked = checked(text)) {
            is Outcome.Failure -> return@withLock checked
            is Outcome.Success -> checked.value
        }
        val current = current()
        current.firstOrNull { it.text.equals(fact, ignoreCase = true) }?.let { return@withLock Outcome.Success(it) }
        if (current.size >= policy.maxItems) return@withLock refused(messages.memoryFull(policy.maxItems))
        val memory = Memory(ids.newId().take(policy.idChars), fact, clock.nowMillis())
        save(current + memory).map { memory }
    }

    /**
     * Rewrites the fact with [id], keeping its id and dating it now; null when nothing had that id. When the new
     * words are another fact's, that one stays and this one goes, so nothing is kept twice.
     */
    suspend fun change(id: String, text: String): Outcome<Memory?> = changes.withLock {
        val fact = when (val checked = checked(text)) {
            is Outcome.Failure -> return@withLock checked
            is Outcome.Success -> checked.value
        }
        val current = current()
        if (current.none { it.id == id }) return@withLock Outcome.Success(null)
        current.firstOrNull { it.id != id && it.text.equals(fact, ignoreCase = true) }?.let { same ->
            return@withLock save(current.filterNot { it.id == id }).map { same }
        }
        val changed = Memory(id, fact, clock.nowMillis())
        save(current.map { if (it.id == id) changed else it }).map { changed }
    }

    /** False when nothing had that id. */
    suspend fun forget(id: String): Outcome<Boolean> = changes.withLock {
        val current = current()
        val kept = current.filterNot { it.id == id }
        if (kept.size == current.size) Outcome.Success(false) else save(kept).map { true }
    }

    suspend fun clear(): Outcome<Unit> = changes.withLock {
        current()
        save(emptyList())
    }

    private suspend fun save(memories: List<Memory>): Outcome<Unit> =
        backend.save(memories).map { _all.value = memories }

    /** [text] as one tidy line, or why it can't be kept. */
    private fun checked(text: String): Outcome<String> {
        val fact = text.trim().replace(WHITESPACE, " ")
        return when {
            fact.isEmpty() -> refused(messages.memoryEmpty)
            fact.length > policy.maxItemChars -> refused(messages.memoryTooLong(policy.maxItemChars))
            else -> Outcome.Success(fact)
        }
    }

    private fun refused(message: String): Outcome<Nothing> = Outcome.Failure(HarnessError.Configuration(message))

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}

/**
 * Saves a lasting fact about the user for future chats. It changes only the agent's own data; once a task has
 * read outside content, policy asks the user first (memory-after-outside-content-confirms), so a web page can't
 * plant a memory. [declined] says whether the user already turned this exact fact down (a dismissed suggestion); then
 * nothing is saved and the model is told so. Only this tool asks: the user's own additions aren't blocked.
 */
class RememberTool(
    private val memory: MemoryStore,
    private val messages: AgentMessages,
    private val declined: suspend (String) -> Boolean = { false },
) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val text = (arguments[TEXT] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (declined(text)) return ToolOutput(messages.memoryDeclined, isError = true, provenance = Provenance.Trusted)
        return when (val saved = memory.remember(text)) {
            is Outcome.Success -> ToolOutput(messages.memorySaved(saved.value.id), isError = false, provenance = Provenance.Trusted)
            is Outcome.Failure -> ToolOutput(saved.error.message, isError = true, provenance = Provenance.Trusted)
        }
    }

    companion object {
        private const val TEXT = "text"

        val DEFINITION = ToolDefinition(
            id = ToolId("remember"),
            description = "Remembers one lasting fact about the user for future chats: a preference, something about them or " +
                "their work, or something they asked you to remember. Write it as a short plain phrase about them, without " +
                "\"The user\": for example \"Prefers metric units\" or \"Is vegetarian\".",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$TEXT":{"type":"string","description":"The fact, in one short phrase"}},"required":["$TEXT"]}""",
            ).jsonObject,
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_remember",
            outputProvenance = Provenance.Trusted,
        )
    }
}

/** Forgets a remembered fact by the id shown beside it in the system prompt. */
class ForgetTool(private val memory: MemoryStore, private val messages: AgentMessages) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val id = (arguments[ID] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (val forgot = memory.forget(id)) {
            is Outcome.Success -> if (forgot.value) {
                ToolOutput(messages.memoryForgotten, isError = false, provenance = Provenance.Trusted)
            } else {
                ToolOutput(messages.memoryNotFound(id), isError = true, provenance = Provenance.Trusted)
            }
            is Outcome.Failure -> ToolOutput(forgot.error.message, isError = true, provenance = Provenance.Trusted)
        }
    }

    companion object {
        private const val ID = "id"

        val DEFINITION = ToolDefinition(
            id = ToolId("forget"),
            description = "Forgets a fact you remembered about the user, by its id.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$ID":{"type":"string","description":"The id shown beside the fact"}},"required":["$ID"]}""",
            ).jsonObject,
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_forget",
            outputProvenance = Provenance.Trusted,
        )
    }
}
