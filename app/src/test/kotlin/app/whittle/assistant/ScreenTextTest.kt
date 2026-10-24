package app.whittle.assistant

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenTextTest {
    @Test
    fun `text is read in order, nested views included`() {
        val screen = ScreenNode(null, false, listOf(ScreenNode("Inbox", false), ScreenNode(null, false, listOf(ScreenNode(" Lunch on Friday? ", false))), ScreenNode("Reply", false)))
        assertEquals("Inbox\nLunch on Friday?\nReply", screenText(listOf(screen), maxChars = 1000))
    }

    @Test
    fun `password fields never reach the model`() {
        val screen = ScreenNode(null, false, listOf(ScreenNode("Sign in", false), ScreenNode("hunter2", true), ScreenNode("Forgot it?", false)))
        assertEquals("Sign in\nForgot it?", screenText(listOf(screen), maxChars = 1000))
    }

    @Test
    fun `repeats in a row are kept once, and the text is capped`() {
        val screen = ScreenNode(null, false, listOf(ScreenNode("Send", false), ScreenNode("Send", false), ScreenNode("Cancel", false)))
        assertEquals("Send\nCancel", screenText(listOf(screen), maxChars = 1000))
        assertEquals("Send\nCa", screenText(listOf(screen), maxChars = 7))
    }
}
