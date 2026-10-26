package app.whittle.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PlanShownTest {
    private fun plan(vararg states: PlanRowState) = states.mapIndexed { index, state -> PlanRow("step ${index + 1}", state) }

    @Test
    fun `done, under way and the next step show, and the rest are counted`() {
        val steps = plan(PlanRowState.Done, PlanRowState.Active, PlanRowState.Pending, PlanRowState.Pending, PlanRowState.Pending)
        val (shown, hidden) = planShown(steps)
        assertEquals(listOf("step 1", "step 2", "step 3"), shown.map { it.text })
        assertEquals(2, hidden)
    }

    @Test
    fun `before anything starts, only the first step shows`() {
        val (shown, hidden) = planShown(plan(PlanRowState.Pending, PlanRowState.Pending, PlanRowState.Pending))
        assertEquals(listOf("step 1"), shown.map { it.text })
        assertEquals(2, hidden)
    }

    @Test
    fun `a finished plan shows every step`() {
        val (shown, hidden) = planShown(plan(PlanRowState.Done, PlanRowState.Done))
        assertEquals(2, shown.size)
        assertEquals(0, hidden)
    }
}
