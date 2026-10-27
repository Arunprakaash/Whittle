package app.whittle.agent

import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryStoreTest {
    /** Keeps facts in a list, as a host's file would. */
    private class ListBackend(var saved: List<Memory> = emptyList()) : MemoryBackend {
        override suspend fun load() = saved

        override suspend fun save(memories: List<Memory>): Outcome<Unit> {
            saved = memories
            return Outcome.Success(Unit)
        }
    }

    private val backend = ListBackend()
    private var next = 0
    private val ids = IdGenerator { "id-${next++}-padding" }

    private fun store(scope: CoroutineScope) = MemoryStore(backend, testMemory, testMessages, EpochClock { 7L }, ids, scope)

    @Test
    fun `facts are saved through the backend, survive a restart and can be forgotten`() = runTest {
        val saved = assertIs<Outcome.Success<Memory>>(store(this).remember("  Prefers   metric units ")).value
        assertEquals(Memory("id-0-pad", "Prefers metric units", 7L), saved)

        val reopened = store(this)
        assertEquals(listOf(saved), reopened.current())
        assertEquals(Outcome.Success(true), reopened.forget(saved.id))
        assertEquals(Outcome.Success(false), reopened.forget(saved.id))
        assertTrue(backend.saved.isEmpty())
    }

    @Test
    fun `the same fact isn't kept twice, and a full or overlong memory says why in the configured words`() = runTest {
        val memory = store(this)
        val first = assertIs<Outcome.Success<Memory>>(memory.remember("Lives in Chennai")).value
        assertEquals(first, assertIs<Outcome.Success<Memory>>(memory.remember("lives in chennai")).value)
        memory.remember("Has a dog")

        assertEquals(HarnessError.Full("full at 2"), assertIs<Outcome.Failure>(memory.remember("Likes tea")).error)
        assertEquals("too long, limit 40", assertIs<Outcome.Failure>(memory.remember("x".repeat(41))).error.message)
        assertEquals("empty", assertIs<Outcome.Failure>(memory.remember("  ")).error.message)
        assertEquals(2, memory.current().size)
    }

    @Test
    fun `a fact can be rewritten in place, within the same limits`() = runTest {
        val memory = store(this)
        val tea = assertIs<Outcome.Success<Memory>>(memory.remember("Likes tea")).value
        val dog = assertIs<Outcome.Success<Memory>>(memory.remember("Has a dog")).value

        val changed = assertIs<Outcome.Success<Memory?>>(memory.change(tea.id, "  Drinks   green tea ")).value
        assertEquals(Memory(tea.id, "Drinks green tea", 7L), changed)
        assertEquals(listOf(changed, dog), memory.current())

        assertEquals("empty", assertIs<Outcome.Failure>(memory.change(tea.id, " ")).error.message)
        assertEquals("too long, limit 40", assertIs<Outcome.Failure>(memory.change(tea.id, "x".repeat(41))).error.message)
        assertEquals(Outcome.Success(null), memory.change("missing", "Anything"))

        // Rewritten into another fact's words, the two become one.
        assertEquals(dog, assertIs<Outcome.Success<Memory?>>(memory.change(tea.id, "has a dog")).value)
        assertEquals(listOf(dog), memory.current())
    }

    @Test
    fun `remember declines a fact the user already turned down and saves the rest`() = runTest {
        val memory = store(this)
        val tool = RememberTool(memory, testMessages, declined = { it.contains("markdrop", ignoreCase = true) })
        val context = ToolExecutionContext(RunId("run"), ToolCallId("call"))
        fun args(text: String) = JsonObject(mapOf("text" to JsonPrimitive(text)))

        val no = tool.execute(args("Works at Markdrop"), context)
        assertEquals("declined", no.content)
        assertTrue(no.isError)
        assertTrue(memory.current().isEmpty())
        assertTrue(backend.saved.isEmpty())

        val yes = tool.execute(args("Likes tea"), context)
        assertEquals("saved id-0-pad", yes.content)
        assertEquals(listOf("Likes tea"), memory.current().map { it.text })
    }
}
