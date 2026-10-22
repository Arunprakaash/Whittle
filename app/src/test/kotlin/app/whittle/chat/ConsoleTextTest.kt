package app.whittle.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleTextTest {
    @Test
    fun `a styled log loses its markers and its CSS`() {
        val message = "%cDownload the React DevTools for a better development experience: https://react.dev/link/react-devtools font-weight:bold"
        assertEquals(
            "Download the React DevTools for a better development experience: https://react.dev/link/react-devtools",
            plainConsoleText(message),
        )
    }

    @Test
    fun `each marker takes one CSS argument, with several declarations in it`() {
        assertEquals("Build done", plainConsoleText("%cBuild %cdone color: green; font-size: 12px font-weight: bold"))
    }

    @Test
    fun `a plain message stays as it is`() {
        assertEquals("error: port 5173 is in use", plainConsoleText("error: port 5173 is in use"))
    }

    @Test
    fun `the message's own words with a colon stay`() {
        assertEquals("Status: ok", plainConsoleText("%cStatus: ok color: red"))
    }
}
