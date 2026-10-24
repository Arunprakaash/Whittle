package app.whittle.automations.events

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationFilterTest {
    private val seen = mutableMapOf<String, String>()

    private fun fire(
        pkg: String = "com.bank",
        ongoing: Boolean = false,
        summary: Boolean = false,
        key: String = "k1",
        text: String = "Paid",
    ) = NotificationFilter.shouldFire(pkg, "app.whittle", ongoing, summary, key, text, seen)

    @Test
    fun repostedNotificationFiresOnce() {
        assertTrue(fire())
        assertFalse(fire())
        assertTrue(fire(text = "Paid 2"))
        assertFalse(fire(text = "Paid 2"))
    }

    @Test
    fun ownAndOngoingAndSummariesNeverFire() {
        assertFalse(fire(pkg = "app.whittle"))
        assertFalse(fire(ongoing = true))
        assertFalse(fire(summary = true))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun rememberedKeysAreCapped() {
        repeat(500) { fire(key = "k$it") }
        assertTrue(seen.size <= NotificationFilter.MAX_KEYS)
        assertTrue("k499" in seen)
    }
}
