package app.whittle.quickstart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickStartTest {
    @Test
    fun `each quick start is known by its action, and nothing else is`() {
        QuickStart.entries.forEach { assertEquals(it, QuickStart.from(it.action)) }
        assertNull(QuickStart.from("android.intent.action.MAIN"))
        assertNull(QuickStart.from(null))
    }
}
