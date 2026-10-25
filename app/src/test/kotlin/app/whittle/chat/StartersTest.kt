package app.whittle.chat

import app.whittle.settings.Starter
import kotlin.test.Test
import kotlin.test.assertEquals

class StartersTest {
    private val calendar = Starter("calendar_month", "What's on today", "What's on my calendar today?", feature = "calendar")
    private val text = Starter("sms", "A quick text", "Draft a text.")

    @Test
    fun `a starter whose phone feature is off isn't offered`() {
        assertEquals(listOf(text), startersFor(listOf(calendar, text), off = setOf("calendar")))
        assertEquals(listOf(calendar, text), startersFor(listOf(calendar, text), off = setOf("contacts")))
    }
}
