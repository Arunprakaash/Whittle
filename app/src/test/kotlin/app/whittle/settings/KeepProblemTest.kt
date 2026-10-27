package app.whittle.settings

import app.whittle.core.common.HarnessError
import kotlin.test.Test
import kotlin.test.assertEquals

class KeepProblemTest {
    private fun problem(error: HarnessError, text: String = "likes tea", remembered: Int = 3) = keepProblem(error, text, remembered, maxItems = 10, maxItemChars = 20)

    @Test
    fun `a suggestion that is gone says so`() {
        assertEquals(KeepProblem.Gone, problem(HarnessError.NotFound("That suggestion is gone")))
    }

    @Test
    fun `words over the limit are too long, even when memory is also full`() {
        assertEquals(KeepProblem.TooLong, problem(HarnessError.Configuration("x"), text = "a".repeat(21), remembered = 10))
    }

    @Test
    fun `a refusal while memory is full is full`() {
        assertEquals(KeepProblem.Full, problem(HarnessError.Configuration("Memory is full (10)"), remembered = 10))
    }

    @Test
    fun `anything else is a plain failure`() {
        assertEquals(KeepProblem.Failed, problem(HarnessError.Storage("disk")))
        assertEquals(KeepProblem.Failed, problem(HarnessError.Configuration("x"), remembered = 3))
    }
}
