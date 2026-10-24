package app.whittle.apps

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class InstallPromptTest {
    private val shown = mutableListOf<String>()

    private fun show(inForeground: Boolean, opens: Boolean = true) =
        showInstallPrompt(inForeground, open = { shown += "open"; opens }, notify = { shown += "notify" })

    @Test
    fun `in the background the prompt goes in a notification, since Android would block it silently`() {
        show(inForeground = false)
        assertEquals(listOf("notify"), shown)
    }

    @Test
    fun `in the foreground the prompt opens at once`() {
        show(inForeground = true)
        assertEquals(listOf("open"), shown)
    }

    @Test
    fun `in the foreground a prompt that fails to open goes in a notification too`() {
        show(inForeground = true, opens = false)
        assertEquals(listOf("open", "notify"), shown)
    }

    @Test
    fun `a result in time is the install's result, and the session stays`() = runTest {
        var abandoned = false
        val result = async {
            awaitInstallResult(10.minutes, start = { deliver -> deliver(InstallResult.INSTALLED) }, stop = { abandoned = true })
        }
        assertEquals(InstallResult.INSTALLED, result.await())
        assertFalse(abandoned)
    }

    @Test
    fun `no result in time abandons the session and counts as cancelled`() = runTest {
        var started = false
        var abandoned = 0
        val result = async {
            awaitInstallResult(10.minutes, start = { started = true }, stop = { abandoned++ })
        }
        advanceTimeBy(10.minutes - 1.minutes)
        assertTrue(started)
        assertEquals(0, abandoned)
        assertEquals(InstallResult.CANCELLED, result.await())
        assertEquals(1, abandoned)
    }
}
