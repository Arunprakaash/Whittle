package app.whittle.tools.api

import app.whittle.core.common.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertIs

class ToolArgumentParserTest {
    private val parser = ToolArgumentParser()
    private val schema = Json.parseToJsonElement(
        """{"type":"object","properties":{"city":{"type":"string"},"days":{"type":"integer"}},"required":["city"]}""",
    ).jsonObject

    @Test
    fun `valid arguments parse`() {
        assertIs<Outcome.Success<*>>(parser.parse("""{"city":"Pune","days":3}""", schema))
    }

    @Test
    fun `malformed json is rejected`() {
        assertIs<Outcome.Failure>(parser.parse("""{"city":""", schema))
    }

    @Test
    fun `missing required property is rejected`() {
        assertIs<Outcome.Failure>(parser.parse("""{"days":3}""", schema))
    }

    @Test
    fun `wrong type is rejected`() {
        assertIs<Outcome.Failure>(parser.parse("""{"city":"Pune","days":"three"}""", schema))
    }

    @Test
    fun `non-object arguments are rejected`() {
        assertIs<Outcome.Failure>(parser.parse("""["Pune"]""", schema))
    }
}
