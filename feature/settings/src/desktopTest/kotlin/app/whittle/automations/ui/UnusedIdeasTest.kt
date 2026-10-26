package app.whittle.automations.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class UnusedIdeasTest {
    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val brief = AutomationIdea("wb_sunny", "Morning brief", AutomationEntry("i1", "Morning brief", "Brief me.", 450, weekdays, setOf("web")))
    private val news = AutomationIdea("newspaper", "News on a topic", AutomationEntry("i2", "News on a topic", "News on: ", 480, (1..7).toSet(), setOf("web")))

    @Test
    fun `an idea the user already made isn't offered again, whatever its case or spacing`() {
        val made = AutomationEntry(id = "a1", name = " morning BRIEF ", prompt = "Brief me.", minuteOfDay = 450, days = weekdays, tools = emptySet())
        assertEquals(listOf(news), unusedIdeas(listOf(brief, news), listOf(made)))
    }

    @Test
    fun `with no automations every idea is offered`() {
        assertEquals(listOf(brief, news), unusedIdeas(listOf(brief, news), emptyList()))
    }
}
