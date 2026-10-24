package app.whittle.automations

import kotlin.test.Test
import kotlin.test.assertEquals

class UnusedIdeasTest {
    private val brief = AutomationIdea("wb_sunny", "Morning brief", "Brief me.", 450, setOf(1, 2, 3, 4, 5), listOf("web"))
    private val news = AutomationIdea("newspaper", "News on a topic", "News on: ", 480, setOf(1, 2, 3, 4, 5, 6, 7), listOf("web"))

    @Test
    fun `an idea the user already made isn't offered again, whatever its case or spacing`() {
        val made = Automation(id = "a1", name = " morning BRIEF ", prompt = "Brief me.", schedule = brief.schedule, tools = emptySet())
        assertEquals(listOf(news), unusedIdeas(listOf(brief, news), listOf(made)))
    }

    @Test
    fun `with no automations every idea is offered`() {
        assertEquals(listOf(brief, news), unusedIdeas(listOf(brief, news), emptyList()))
    }
}
