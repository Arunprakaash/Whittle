package app.whittle.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, page: String = "https://github.com/Arunprakaash/Whittle/releases/tag/$tag") =
        """{"tag_name":"$tag","html_url":"$page","draft":$draft,"prerelease":$prerelease,"assets":[],"body":"## What's new"}"""

    @Test
    fun `the latest release gives its version and page`() {
        assertEquals(
            Release("0.21.0", "https://github.com/Arunprakaash/Whittle/releases/tag/v0.21.0"),
            parseLatestRelease(release("v0.21.0")),
        )
    }

    @Test
    fun `drafts, pre-releases, odd tags, pages that are not https and broken replies are ignored`() {
        assertNull(parseLatestRelease(release("v0.21.0", draft = true)))
        assertNull(parseLatestRelease(release("v0.21.0", prerelease = true)))
        assertNull(parseLatestRelease(release("nightly")))
        assertNull(parseLatestRelease(release("v0.21.0", page = "http://github.com/x/releases/tag/v0.21.0")))
        assertNull(parseLatestRelease(release("v0.21.0", page = "intent://evil#Intent;end")))
        assertNull(parseLatestRelease("""{"message":"API rate limit exceeded"}"""))
        assertNull(parseLatestRelease("<html>"))
    }

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(isNewer("0.21.0", "0.20.0"))
        assertTrue(isNewer("0.20.1", "0.20.0"))
        assertTrue(isNewer("0.100.0", "0.99.9"))
        assertTrue(isNewer("1.0", "0.20.0"))
        assertFalse(isNewer("0.20.0", "0.20.0"))
        assertFalse(isNewer("0.19.0", "0.20.0"))
        assertFalse(isNewer("0.20.0", "0.20.0-debug"))
        assertFalse(isNewer("nightly", "0.20.0"))
    }

    @Test
    fun `a check is due after the interval, or when the clock went back`() {
        val hour = 3_600_000L
        assertTrue(due(checkedAtMillis = 0, nowMillis = 24 * hour, everyHours = 24))
        assertFalse(due(checkedAtMillis = 0, nowMillis = 23 * hour, everyHours = 24))
        assertTrue(due(checkedAtMillis = 10 * hour, nowMillis = 5 * hour, everyHours = 24))
    }
}
