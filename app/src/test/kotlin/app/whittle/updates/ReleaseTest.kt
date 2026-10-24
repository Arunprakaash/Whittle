package app.whittle.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTest {
    private val apk = Regex("^Whittle-.*\\.apk$")

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, assets: String = APK_ASSET) =
        """{"tag_name":"$tag","html_url":"https://github.com/Arunprakaash/Whittle/releases/tag/$tag","draft":$draft,""" +
            """"prerelease":$prerelease,"assets":[$assets],"body":"## What's new"}"""

    @Test
    fun `the latest release gives its version, page and APK`() {
        assertEquals(
            Release("0.21.0", "https://github.com/Arunprakaash/Whittle/releases/tag/v0.21.0", "https://example.com/Whittle-0.21.0.apk"),
            parseLatestRelease(release("v0.21.0"), apk),
        )
    }

    @Test
    fun `a release without a matching APK still points at its page`() {
        assertNull(parseLatestRelease(release("v0.21.0", assets = """{"name":"notes.txt","browser_download_url":"https://example.com/n"}"""), apk)?.apkUrl)
    }

    @Test
    fun `drafts, pre-releases, odd tags and broken replies are ignored`() {
        assertNull(parseLatestRelease(release("v0.21.0", draft = true), apk))
        assertNull(parseLatestRelease(release("v0.21.0", prerelease = true), apk))
        assertNull(parseLatestRelease(release("nightly"), apk))
        assertNull(parseLatestRelease("""{"message":"API rate limit exceeded"}""", apk))
        assertNull(parseLatestRelease("<html>", apk))
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

    private companion object {
        const val APK_ASSET = """{"name":"Whittle-0.21.0.apk","browser_download_url":"https://example.com/Whittle-0.21.0.apk"}"""
    }
}
