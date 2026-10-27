package app.whittle.automations.events

import kotlin.test.Test
import kotlin.test.assertEquals

class SmsPartsTest {
    @Test
    fun multipartSmsJoinsIntoOneEvent() {
        val parts = listOf("HDFCBK" to "Spent Rs 500 at ", "AMZN" to "Hi", "HDFCBK" to "the shop. ", "HDFCBK" to "Bal 90")
        assertEquals(listOf("HDFCBK" to "Spent Rs 500 at the shop. Bal 90", "AMZN" to "Hi"), joinParts(parts))
    }

    @Test
    fun singlePartStaysAsItIs() {
        assertEquals(listOf("A" to "x"), joinParts(listOf("A" to "x")))
    }
}
