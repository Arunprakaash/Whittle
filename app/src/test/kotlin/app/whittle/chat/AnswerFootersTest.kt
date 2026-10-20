package app.whittle.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class AnswerFootersTest {
    private fun answer(key: String, text: String) = ThreadItem.Narration(key, text, streaming = false, answer = true)

    @Test
    fun `a turn that ran several times gets one footer under its last answer`() {
        val items = listOf(
            ThreadItem.User("u1", "Message Amma", steer = false),
            answer("a1", "Pick a contact."),
            ThreadItem.Sources("s1", listOf(SourceModel("https://a.com/", "A", "a.com"))),
            answer("a2", "It's ready in Messages."),
            ThreadItem.Sources("s2", listOf(SourceModel("https://a.com/", "A", "a.com"), SourceModel("https://b.com/", "B", "b.com")), expanded = true),
            answer("a3", "Opened it for you."),
            ThreadItem.User("u2", "Thanks", steer = false),
            answer("a4", "Anytime."),
        )

        val plan = AnswerFooters.plan(items)

        assertEquals(setOf("a3", "a4"), plan.footers.keys)
        assertEquals(setOf("s1", "s2"), plan.folded)
        val footer = plan.footers.getValue("a3")
        assertEquals("Pick a contact.\n\nIt's ready in Messages.\n\nOpened it for you.", footer.copyText)
        assertEquals(listOf("https://a.com/", "https://b.com/"), footer.sources.map { it.url })
        assertEquals("s2", footer.sourcesKey)
        assertEquals(true, footer.sourcesExpanded)
        assertEquals("Anytime.", plan.footers.getValue("a4").copyText)
    }

    @Test
    fun `streaming narration and steers don't end a turn`() {
        val items = listOf(
            ThreadItem.User("u1", "Plan a trip", steer = false),
            answer("a1", "Here's a start."),
            ThreadItem.User("s", "Make it cheaper", steer = true),
            ThreadItem.Narration("n2", "Looking", streaming = true, answer = false),
        )
        assertEquals(setOf("a1"), AnswerFooters.plan(items).footers.keys)
    }
}
