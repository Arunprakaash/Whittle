package app.whittle.quickstart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickStartTest {
    @Test
    fun `each quick start is known by its action, and nothing else is`() {
        QuickStart.entries.forEach { assertEquals(it, QuickStart.from(it.action, QuickStart.ENTRY)) }
        assertNull(QuickStart.from("android.intent.action.MAIN", QuickStart.ENTRY))
        assertNull(QuickStart.from(null, QuickStart.ENTRY))
    }

    @Test
    fun `an action sent straight to MainActivity, as another app could, starts nothing`() {
        QuickStart.entries.forEach {
            assertNull(QuickStart.from(it.action, "app.whittle.MainActivity"))
            assertNull(QuickStart.from(it.action, null))
        }
    }
}
