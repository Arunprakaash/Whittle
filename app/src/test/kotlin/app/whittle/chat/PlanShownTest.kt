package app.whittle.chat

import app.whittle.agent.PlanItem
import app.whittle.agent.PlanStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanShownTest {
    private fun plan(vararg statuses: PlanStatus) = statuses.mapIndexed { index, status -> PlanItem("step ${index + 1}", status) }

    @Test
    fun `done, under way and the next step show, and the rest are counted`() {
        val steps = plan(PlanStatus.COMPLETED, PlanStatus.IN_PROGRESS, PlanStatus.PENDING, PlanStatus.PENDING, PlanStatus.PENDING)
        val (shown, hidden) = planShown(steps)
        assertEquals(listOf("step 1", "step 2", "step 3"), shown.map { it.content })
        assertEquals(2, hidden)
    }

    @Test
    fun `before anything starts, only the first step shows`() {
        val (shown, hidden) = planShown(plan(PlanStatus.PENDING, PlanStatus.PENDING, PlanStatus.PENDING))
        assertEquals(listOf("step 1"), shown.map { it.content })
        assertEquals(2, hidden)
    }

    @Test
    fun `a finished plan shows every step`() {
        val (shown, hidden) = planShown(plan(PlanStatus.COMPLETED, PlanStatus.COMPLETED))
        assertEquals(2, shown.size)
        assertEquals(0, hidden)
    }
}
