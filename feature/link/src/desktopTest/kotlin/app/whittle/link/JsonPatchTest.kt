package app.whittle.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonPatchTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun roundTrip(old: String, new: String): JsonElement {
        val patch = JsonPatch.diff(json(old), json(new))!!
        assertEquals(json(new), JsonPatch.apply(json(old), patch))
        return patch
    }

    @Test
    fun `the same value needs no patch`() {
        assertNull(JsonPatch.diff(json("""{"a":[1,{"b":"c"}]}"""), json("""{"a":[1,{"b":"c"}]}""")))
    }

    @Test
    fun `a reply streaming in sends only the new words`() {
        val patch = roundTrip(
            """{"items":[{"id":"1","text":"Hello"},{"id":"2","text":"Working on it"}],"busy":true}""",
            """{"items":[{"id":"1","text":"Hello"},{"id":"2","text":"Working on it, nearly done"}],"busy":true}""",
        )
        val sent = patch.toString()
        assertTrue(", nearly done" in sent && "Hello" !in sent && "Working on it\"" !in sent, sent)
    }

    @Test
    fun `items added, changed and dropped, and keys gone, all land`() {
        roundTrip("""{"items":[1,2,3],"title":"a","gone":true}""", """{"items":[1,5,3,4],"title":"b"}""")
        roundTrip("""{"items":[1,2,3]}""", """{"items":[1]}""")
        roundTrip("""{"items":[{"a":1}]}""", """{"items":[{"a":1},{"b":2}]}""")
    }

    @Test
    fun `a value changing kind is sent whole`() {
        roundTrip("""{"a":{"b":1}}""", """{"a":[1,2]}""")
        roundTrip("""{"a":"text"}""", """{"a":null}""")
        roundTrip("""{"a":"long text"}""", """{"a":"short"}""")
    }

    @Test
    fun `the first value is sent whole`() {
        val value = json("""{"a":1}""")
        assertEquals(value, JsonPatch.apply(null, JsonPatch.diff(null, value)!!))
    }
}
