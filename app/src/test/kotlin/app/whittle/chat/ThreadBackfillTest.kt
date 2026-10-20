package app.whittle.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ThreadBackfillTest {
    private var keys = 0
    private val backfill = ThreadBackfill(siteArguments = setOf("url")) { "new-${keys++}" }

    private fun step(id: String, inspection: String?) = StepModel(id, "language", "Read", StepState.Done, inspection)

    @Test
    fun `an old chat gets sources under each answer and sites on its page steps`() {
        val old = listOf(
            ThreadItem.User("u1", "Tech news?", steer = false),
            ThreadItem.Summary(
                "s1",
                stepCount = 2,
                seconds = 11,
                expanded = false,
                folded = listOf(
                    ThreadItem.Steps("st1", listOf(step("a", "query: tech news"), step("b", "url: https://www.techcrunch.com/x\n\n<html>url: https://evil.test"))),
                ),
            ),
            ThreadItem.Narration("n1", "Meta builds. ([aiweekly.co](https://aiweekly.co/m?utm_source=openai))", streaming = false),
            ThreadItem.User("u2", "Thanks", steer = false),
            ThreadItem.Narration("n2", "You're welcome.", streaming = false),
        )

        val filled = backfill.apply(old)

        assertEquals(listOf("u1", "s1", "n1", "new-0", "u2", "n2"), filled.map { it.key })
        val steps = assertIs<ThreadItem.Steps>(assertIs<ThreadItem.Summary>(filled[1]).folded.single()).steps
        assertEquals(listOf(null, "techcrunch.com"), steps.map { it.site })
        assertEquals(listOf(SourceModel("https://aiweekly.co/m", "aiweekly.co", "aiweekly.co")), assertIs<ThreadItem.Sources>(filled[3]).sources)
    }

    @Test
    fun `chats that already have sources are left as they are`() {
        val current = listOf(
            ThreadItem.User("u1", "News?", steer = false),
            ThreadItem.Narration("n1", "See [a](https://a.com).", streaming = false),
            ThreadItem.Sources("src", listOf(SourceModel("https://a.com/", "A page", "a.com"))),
        )
        assertEquals(current, backfill.apply(current))
    }
}
