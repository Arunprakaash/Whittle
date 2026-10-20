package app.whittle.tools.presentation

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolPresenterTest {
    private val decision = DecisionPresentation(
        icon = "sms",
        title = "Send this to {{to}}?",
        body = "Opens Messages with the text ready.",
        payloadArgument = "text",
        payloadLabelArgument = "to",
        approve = "Open in Messages",
        approveIcon = "open_in_new",
        decline = "Not now",
        approvedOutcome = "Opened in Messages",
        declinedOutcome = "Not sent",
    )
    private val presenter = ToolPresenter(
        ToolPresentationConfig(
            schemaVersion = ToolPresentationConfig.SCHEMA_VERSION,
            fallback = ToolPresentation(
                icon = "build",
                running = "Working",
                done = "Done",
                decision = decision.copy(title = "Allow this action?", payloadArgument = null, payloadLabelArgument = null, icon = "help"),
            ),
            tools = mapOf(
                "web_fetch" to ToolPresentation("language", "Reading {{url_host}}", "Read {{url_host}}", siteArgument = "url"),
                "compose_message" to ToolPresentation("sms", "Writing to {{to}}", "Wrote to {{to}}", decision),
            ),
        ),
    )

    @Test
    fun `labels use argument values and url hosts`() {
        val step = presenter.step("web_fetch", args("""{"url":"https://www.example.com/a?b=1"}"""))
        assertEquals(StepText("language", "Reading example.com", "Read example.com", site = "example.com"), step)
    }

    @Test
    fun `steps that read a page name its site, others don't`() {
        val read = presenter.step("web_fetch", JsonObject(mapOf("url" to JsonPrimitive("https://www.techcrunch.com/a"))))
        assertEquals("techcrunch.com", read.site)
        assertEquals(null, presenter.step("compose_message", JsonObject(mapOf("to" to JsonPrimitive("Asha")))).site)
    }

    @Test
    fun `a missing argument falls back instead of showing a placeholder`() {
        val step = presenter.step("web_fetch", args("{}"))
        assertEquals("Working", step.running)
    }

    @Test
    fun `unknown tools never show their id`() {
        assertEquals(StepText("build", "Working", "Done"), presenter.step("internal.tool_42", args("{}")))
    }

    @Test
    fun `decision cards show the exact payload`() {
        val card = presenter.decision("compose_message", args("""{"to":"Priya Sharma","text":"Running 15 minutes late"}"""))
        assertEquals("Send this to Priya Sharma?", card.title)
        assertEquals("Running 15 minutes late", card.payload)
        assertEquals("Priya Sharma", card.payloadLabel)
    }

    @Test
    fun `the fallback card lists the call's arguments`() {
        val card = presenter.decision("unlisted", args("""{"path":"/sdcard/a.txt"}"""))
        assertEquals("Allow this action?", card.title)
        assertEquals("path: /sdcard/a.txt", card.payload)
        assertNull(card.payloadLabel)
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
}
