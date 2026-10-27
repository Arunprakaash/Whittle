package app.whittle.settings

import app.whittle.core.common.HarnessError
import kotlin.test.Test
import kotlin.test.assertEquals

class KeepProblemTest {
    private fun problem(error: HarnessError, text: String = "likes tea") = keepProblem(error, text, maxItemChars = 20)

    @Test
    fun `a suggestion that is gone says so`() {
        assertEquals(KeepProblem.Gone, problem(HarnessError.NotFound("That suggestion is gone")))
    }

    @Test
    fun `memory saying it is full is full, whatever the words`() {
        assertEquals(KeepProblem.Full, problem(HarnessError.Full("Memory is full (10)")))
        assertEquals(KeepProblem.Full, problem(HarnessError.Full("Memory is full (10)"), text = "a".repeat(21)))
    }

    @Test
    fun `words over the limit are too long`() {
        assertEquals(KeepProblem.TooLong, problem(HarnessError.Configuration("x"), text = "a".repeat(21)))
    }

    @Test
    fun `anything else is a plain failure`() {
        assertEquals(KeepProblem.Failed, problem(HarnessError.Storage("disk")))
        assertEquals(KeepProblem.Failed, problem(HarnessError.Configuration("x")))
    }
}
