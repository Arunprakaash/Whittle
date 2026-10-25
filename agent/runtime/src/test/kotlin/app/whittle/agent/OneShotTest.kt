package app.whittle.agent

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ReasoningEffort
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OneShotTest {
    private fun oneShot(adapter: ScriptedAdapter) = OneShot(ModelAdapterRegistry(setOf(adapter)), testCatalog, fixedKey)

    @Test
    fun concatenatesText() = runTest {
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("Hel"), ModelEvent.TextDelta("lo"))))

        val result = oneShot(adapter).complete(testModel(), "sys", "hi", 64)

        assertEquals("Hello", assertIs<Outcome.Success<String>>(result).value)
        val request = adapter.requests.single()
        assertEquals("sys", request.systemPrompt)
        assertEquals("hi", request.messages.single().text)
        assertEquals(64, request.maxOutputTokens)
        assertEquals(ReasoningEffort.LOW, request.reasoningEffort)
        assertTrue(request.tools.isEmpty())
    }

    @Test
    fun failureIsReported() = runTest {
        val error = HarnessError.Network("down")
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("par"), ModelEvent.Failed(error))))

        val result = oneShot(adapter).complete(testModel(), "sys", "hi", 64)

        assertEquals(error, assertIs<Outcome.Failure>(result).error)
    }
}
