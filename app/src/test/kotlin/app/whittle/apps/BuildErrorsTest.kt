package app.whittle.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildErrorsTest {
    @Test
    fun `parses kotlinc errors and caps them`() {
        val stderr = buildString {
            appendLine("warning: something")
            (1..5).forEach { appendLine("src/Main.kt:$it:3: error: unresolved reference: x$it") }
            appendLine("    val a = x1")
            appendLine("res/layout/a.xml:4: error: resource not found")
        }
        val parsed = BuildErrors.parse(stderr, maxErrors = 3, maxChars = 1000)
        assertEquals(
            listOf("src/Main.kt:1:3: error: unresolved reference: x1", "src/Main.kt:2:3: error: unresolved reference: x2", "src/Main.kt:3:3: error: unresolved reference: x3"),
            parsed.lines(),
        )
        assertFalse("warning" in parsed)
    }

    @Test
    fun `keeps aapt2 errors`() {
        val parsed = BuildErrors.parse("AndroidManifest.xml:5: error: attribute foo not found.\nnoise", 10, 1000)
        assertEquals("AndroidManifest.xml:5: error: attribute foo not found.", parsed)
    }

    @Test
    fun `caps the characters`() {
        val parsed = BuildErrors.parse("a.kt:1:1: error: " + "z".repeat(500), 10, 50)
        assertEquals(50, parsed.length)
    }

    @Test
    fun `falls back to the stderr tail when nothing parses`() {
        val parsed = BuildErrors.parse("start\n" + "x".repeat(100) + "\nend of output", 10, 20)
        assertEquals(20, parsed.length)
        assertTrue(parsed.endsWith("end of output"))
    }
}
