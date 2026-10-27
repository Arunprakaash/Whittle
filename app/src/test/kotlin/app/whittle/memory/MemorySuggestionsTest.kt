package app.whittle.memory

import app.whittle.agent.AgentMessages
import app.whittle.agent.Memory
import app.whittle.agent.MemoryBackend
import app.whittle.agent.MemoryStore
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.config.MemoryPolicy
import app.whittle.settings.MemoryReviewDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemorySuggestionsTest {
    private class MemoryList(var saved: List<Memory> = emptyList()) : MemoryBackend {
        override suspend fun load() = saved

        override suspend fun save(memories: List<Memory>): Outcome<Unit> {
            saved = memories
            return Outcome.Success(Unit)
        }
    }

    private class StateBox(var saved: SuggestionsState = SuggestionsState(emptyList(), emptyList(), emptyMap())) : SuggestionsBackend {
        var failing = false

        override suspend fun load() = saved

        override suspend fun save(state: SuggestionsState): Outcome<Unit> {
            if (failing) return Outcome.Failure(HarnessError.Storage("disk full"))
            saved = state
            return Outcome.Success(Unit)
        }
    }

    private val messages = assertIs<Outcome.Success<AgentMessages>>(
        ConfigLoader(BundledConfigSource()).load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION),
    ).value
    private val memoryBackend = MemoryList()
    private val box = StateBox()
    private val deleted = mutableSetOf<String>()
    private var next = 0
    private val ids = IdGenerator { "id-${next++}-padding" }
    private val review = MemoryReviewDefaults(
        fileName = "x.bin", suggest = false, models = emptyMap(), quietMillis = 1, maxSuggestions = 3, maxInputChars = 10, contextChars = 5, maxOutputTokens = 10, maxDismissed = 2, maxDeclinedInInput = 1, maxReviewedChats = 2,
    )

    private fun memory(scope: CoroutineScope, items: Int = 5, chars: Int = 40) =
        MemoryStore(memoryBackend, MemoryPolicy(items, chars, 8), messages, EpochClock { 7L }, ids, scope)

    private fun suggestions(scope: CoroutineScope, memory: MemoryStore = memory(scope)) =
        MemorySuggestions(box, memory, review, EpochClock { 9L }, ids, scope) { it !in deleted }

    @Test
    fun `an edited keep also dismisses the original wording`() = runTest {
        val s = suggestions(this)
        s.add("chat", listOf("Is vegetarian" to null, "Likes tea" to null))
        val (veg, tea) = s.pending.value
        assertIs<Outcome.Success<Memory>>(s.keep(veg.id, "Is vegetarian, no eggs"))
        assertIs<Outcome.Success<Memory>>(s.keep(tea.id, "  likes TEA "))
        // Only the edited one records the original; an unchanged keep (up to case and spacing) is not a dismissal.
        assertEquals(listOf("is vegetarian"), box.saved.dismissed)
        s.add("chat", listOf("Is vegetarian" to null))
        assertTrue(s.pending.value.isEmpty())
    }

    @Test
    fun `only the exact dismissed fact is refused, ignoring case and spacing`() = runTest {
        val s = suggestions(this)
        s.add("chat", listOf("works at markdrop" to null, "Likes tea" to null, "Lives in Chennai" to null))
        s.pending.value.forEach { s.dismiss(it.id) }
        // maxDismissed is 2 in this fixture: only the last two remain.
        assertTrue(s.wasDismissed("Likes tea"))
        assertTrue(s.wasDismissed("  LIKES   Tea "))
        assertTrue(s.wasDismissed("Lives in Chennai"))
        assertFalse(s.wasDismissed("Works at Markdrop"), "the oldest was capped away")
        // Other wordings may be what the user asked for now; the review prompt's declined list handles rewording.
        assertFalse(s.wasDismissed("Doesn't like tea anymore"))
        assertFalse(s.wasDismissed("Likes green tea"))
        assertFalse(s.wasDismissed("   "))
        assertFalse(s.wasDismissed(""))
    }

    @Test
    fun `a fact the user kept or never saw is not dismissed`() = runTest {
        val s = suggestions(this)
        s.add("chat", listOf("Likes tea" to null))
        assertFalse(s.wasDismissed("Likes tea"))
        s.keep(s.pending.value.single().id, "Likes tea")
        assertFalse(s.wasDismissed("Likes tea"))
    }

    @Test
    fun `seen gives what waits and the newest declined`() = runTest {
        val s = suggestions(this)
        s.add("chat", listOf("One" to null, "Two" to null, "Three" to null, "Four" to null))
        s.pending.value.take(2).forEach { s.dismiss(it.id) }
        assertEquals(SeenSuggestions(listOf("Three", "Four"), listOf("two")), s.seen())
    }

    @Test
    fun `forgetting a chat drops its waiting suggestions and review count`() = runTest {
        val s = suggestions(this)
        s.add("a", listOf("From a" to null))
        s.add("b", listOf("From b" to null))
        s.markReviewed("a", 2)
        s.markReviewed("b", 3)
        assertIs<Outcome.Success<Unit>>(s.forgetChat("a"))
        assertEquals(listOf("From b"), s.pending.value.map { it.text })
        assertEquals(0, s.reviewedCount("a"))
        assertEquals(3, s.reviewedCount("b"))
        assertEquals(listOf("From b"), box.saved.pending.map { it.text })
    }

    @Test
    fun `forgetting every chat clears what waits but not what was dismissed`() = runTest {
        val s = suggestions(this)
        s.add("a", listOf("From a" to null, "Gone" to null))
        s.dismiss(s.pending.value.last().id)
        s.markReviewed("a", 2)
        assertIs<Outcome.Success<Unit>>(s.forgetAllChats())
        assertTrue(s.pending.value.isEmpty())
        assertEquals(0, s.reviewedCount("a"))
        assertEquals(listOf("gone"), box.saved.dismissed)
    }

    @Test
    fun `keepSavesMemoryAndClears`() = runTest {
        val memory = memory(this)
        val s = suggestions(this, memory)
        s.add("chat", listOf("Prefers metric units" to null))
        val id = s.pending.value.single().id
        val kept = assertIs<Outcome.Success<Memory>>(s.keep(id, "Prefers metric units, always")).value
        assertEquals("Prefers metric units, always", kept.text)
        assertEquals(listOf(kept), memory.current())
        assertTrue(s.pending.value.isEmpty())
        assertTrue(box.saved.pending.isEmpty())
    }

    @Test
    fun `keepWithReplacesChangesOld`() = runTest {
        val memory = memory(this)
        val old = assertIs<Outcome.Success<Memory>>(memory.remember("Lives in Chennai")).value
        val s = suggestions(this, memory)
        s.add("chat", listOf("Lives in Pune" to old.id))
        s.keep(s.pending.value.single().id, "Lives in Pune")
        assertEquals(listOf(Memory(old.id, "Lives in Pune", 7L)), memory.current())
    }

    @Test
    fun `a replaced memory that is gone falls back to saving the new one`() = runTest {
        val memory = memory(this)
        val s = suggestions(this, memory)
        s.add("chat", listOf("Lives in Pune" to "missing"))
        assertIs<Outcome.Success<Memory>>(s.keep(s.pending.value.single().id, "Lives in Pune"))
        assertEquals(listOf("Lives in Pune"), memory.current().map { it.text })
    }

    @Test
    fun `a failed keep leaves the suggestion pending and says why`() = runTest {
        val memory = memory(this, items = 1)
        memory.remember("Has a dog")
        val s = suggestions(this, memory)
        s.add("chat", listOf("Likes tea" to null))
        assertIs<Outcome.Failure>(s.keep(s.pending.value.single().id, "Likes tea"))
        assertEquals(1, s.pending.value.size)
        assertIs<HarnessError.NotFound>(assertIs<Outcome.Failure>(s.keep("nope", "x")).error)
    }

    @Test
    fun `dismissedNeverReturns`() = runTest {
        val s = suggestions(this)
        s.add("chat", listOf("Likes tea" to null))
        s.dismiss(s.pending.value.single().id)
        assertTrue(s.pending.value.isEmpty())
        s.add("chat", listOf("  likes TEA " to null))
        assertTrue(s.pending.value.isEmpty())
        assertEquals(listOf("likes tea"), box.saved.dismissed)
    }

    @Test
    fun `a dismiss that can't be saved stays pending and says so`() = runTest {
        val s = suggestions(this)
        s.add("chat", listOf("Likes tea" to null))
        val id = s.pending.value.single().id
        box.failing = true
        assertIs<Outcome.Failure>(s.dismiss(id))
        assertEquals(listOf(id), s.pending.value.map { it.id })
        assertTrue(box.saved.dismissed.isEmpty())
        assertIs<Outcome.Failure>(s.keep(id, "Likes tea"))
        assertEquals(1, s.pending.value.size)
    }

    @Test
    fun `duplicatesOfMemoriesSkipped`() = runTest {
        val memory = memory(this)
        memory.remember("Has a dog")
        val s = suggestions(this, memory)
        s.add("chat", listOf("has a DOG" to null, "Likes tea" to null, "likes tea" to null, "x".repeat(41) to null, "  " to null))
        assertEquals(listOf("Likes tea"), s.pending.value.map { it.text })
        s.add("chat", listOf("LIKES TEA" to null))
        assertEquals(1, s.pending.value.size)
    }

    @Test
    fun `what is kept in mind is capped and survives a restart`() = runTest {
        val s = suggestions(this)
        listOf("a", "b", "c").forEach { text ->
            s.add("chat", listOf(text to null))
            s.dismiss(s.pending.value.single().id)
        }
        assertEquals(listOf("b", "c"), box.saved.dismissed)
        s.markReviewed("one", 10)
        s.markReviewed("two", 20)
        s.markReviewed("three", 30)
        s.markReviewed("two", 25)
        assertEquals(0, s.reviewedCount("one"))
        assertEquals(25, s.reviewedCount("two"))
        assertEquals(30, suggestions(this).reviewedCount("three"))
    }

    @Test
    fun `the encrypted file keeps the state`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("whittle-suggestions").toFile()
        try {
            val cipher = object : app.whittle.core.common.ContentCipher {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Outcome<ByteArray> = Outcome.Success(plaintext.reversedArray())

                override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): Outcome<ByteArray> = Outcome.Success(ciphertext.reversedArray())
            }
            val state = SuggestionsState(listOf(MemorySuggestion("a", "Likes tea", null, "c", 1L)), listOf("x"), mapOf("c" to 4))
            EncryptedFileSuggestions(java.io.File(dir, "s.bin"), cipher).save(state)
            assertEquals(state, EncryptedFileSuggestions(java.io.File(dir, "s.bin"), cipher).load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
