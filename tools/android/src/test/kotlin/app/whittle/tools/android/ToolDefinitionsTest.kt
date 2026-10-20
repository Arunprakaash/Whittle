package app.whittle.tools.android

import app.whittle.tools.api.RiskTier
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolDefinitionsTest {
    @Test
    fun `reading tools declare the capability they need`() {
        assertEquals(setOf(AndroidCapabilities.CONTACTS), ContactsSearchTool.DEFINITION.requiredCapabilities)
        assertEquals(setOf(AndroidCapabilities.CALENDAR), CalendarEventsTool.DEFINITION.requiredCapabilities)
    }

    @Test
    fun `contact and calendar data is untrusted`() {
        assertTrue(ContactsSearchTool.DEFINITION.outputProvenance is app.whittle.core.model.Provenance.Untrusted)
        assertTrue(CalendarEventsTool.DEFINITION.outputProvenance is app.whittle.core.model.Provenance.Untrusted)
    }

    @Test
    fun `compose message only drafts and needs every argument`() {
        val definition = ComposeMessageTool.DEFINITION
        assertEquals(RiskTier.WRITE_LOCAL, definition.riskTier)
        val required = definition.inputSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("recipientName", "phoneNumber", "text"), required)
        assertTrue(definition.inputSchema.getValue("properties").jsonObject.keys.containsAll(required))
    }
}
