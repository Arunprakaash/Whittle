package app.whittle.design.components

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightMarkdownTest {
    private val code = SpanStyle(fontWeight = FontWeight(400))

    @Test
    fun `markers are removed and their text styled`() {
        val result = lightMarkdown("Use **Read** and *then* `web_fetch`.", code)
        assertEquals("Use Read and then web_fetch.", result.text)
        val bold = result.spanStyles.single { it.item.fontWeight == FontWeight(600) }
        assertEquals("Read", result.text.substring(bold.start, bold.end))
        val italic = result.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("then", result.text.substring(italic.start, italic.end))
    }

    @Test
    fun `lists get a bullet and keep their numbers`() {
        assertEquals("•  a full-screen clock\n•  a widget\n2. both", lightMarkdown("- a full-screen clock\n* a widget\n2) both", code).text)
    }

    @Test
    fun `code fences drop the fence lines`() {
        assertEquals("Here:\nval x = 1", lightMarkdown("Here:\n```kotlin\nval x = 1\n```", code).text)
    }

    @Test
    fun `an unclosed marker mid-stream stays literal`() {
        assertEquals("It is **ele", lightMarkdown("It is **ele", code).text)
    }

    @Test
    fun `arithmetic and snake case are not emphasis`() {
        val result = lightMarkdown("2 * 3 * 4 and device_clock_now", code)
        assertEquals("2 * 3 * 4 and device_clock_now", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `web links become tappable text and other schemes stay literal`() {
        val result = lightMarkdown("Sunny ([accuweather.com](https://www.accuweather.com/en/in/chennai)) and [x](javascript:alert)", code)
        assertEquals("Sunny (accuweather.com) and [x](javascript:alert)", result.text)
        val link = result.getLinkAnnotations(0, result.length).single()
        assertEquals("https://www.accuweather.com/en/in/chennai", (link.item as LinkAnnotation.Url).url)
        assertEquals("accuweather.com", result.text.substring(link.start, link.end))
    }
}
