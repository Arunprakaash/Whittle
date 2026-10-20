package app.whittle.core.config

import app.whittle.core.common.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PromptTemplateTest {
    private val name = ConfigName("test.md")

    @Test
    fun `renders every placeholder`() {
        val result = PromptTemplate(name, "Hello {{name}}, today is {{ day }}.").render(mapOf("name" to "Asha", "day" to "Monday"))
        assertEquals(Outcome.Success("Hello Asha, today is Monday."), result)
    }

    @Test
    fun `missing value is a configuration error`() {
        assertIs<Outcome.Failure>(PromptTemplate(name, "Hi {{name}}").render(emptyMap()))
    }

    @Test
    fun `layered source prefers earlier layers`() {
        val override = ConfigSource { Outcome.Success("override") }
        val bundled = ConfigSource { Outcome.Success("bundled") }
        assertEquals(Outcome.Success("override"), LayeredConfigSource(listOf(override, bundled)).read(name))
    }
}
