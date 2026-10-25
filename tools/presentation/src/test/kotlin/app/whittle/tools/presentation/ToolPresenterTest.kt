package app.whittle.tools.presentation

import app.whittle.tools.api.ToolOrigin
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
            toolPrefixes = mapOf(
                "mcp_" to ToolPresentation(
                    "extension",
                    "{{toolTitle}} in {{toolSource}}",
                    "{{toolTitle}} in {{toolSource}}",
                    decision.copy(title = "{{toolTitle}} in {{toolSource}}?", payloadArgument = null, payloadLabelArgument = null),
                ),
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
    fun `argumentWords replace an argument's value in the card, and unknown values stay as given`() {
        val watch = decision.copy(
            title = "Watch for this?",
            body = "Whittle will run this by itself {{event}}.",
            payloadArgument = "prompt",
            payloadLabelArgument = "from",
            argumentWords = mapOf("event" to mapOf("text" to "when a text arrives")),
        )
        val withWords = ToolPresenter(
            ToolPresentationConfig(
                schemaVersion = ToolPresentationConfig.SCHEMA_VERSION,
                fallback = ToolPresentation("build", "Working", "Done", decision),
                tools = mapOf("watch_for" to ToolPresentation("visibility", "Setting up", "Set up", watch)),
            ),
        )
        assertEquals("Whittle will run this by itself when a text arrives.", withWords.decision("watch_for", args("""{"event":"text","prompt":"p"}""")).body)
        assertEquals("Whittle will run this by itself later.", withWords.decision("watch_for", args("""{"event":"later","prompt":"p"}""")).body)
        // The filter is optional: absent, the card has no label; given, it shows above the prompt.
        assertNull(withWords.decision("watch_for", args("""{"event":"text","prompt":"p"}""")).payloadLabel)
        assertEquals("HDFCBK", withWords.decision("watch_for", args("""{"event":"text","prompt":"p","from":"HDFCBK"}""")).payloadLabel)
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

    @Test
    fun `a connected tool goes by its server's own names and logo`() {
        val origin = ToolOrigin(title = "Create pages", source = "Notion", site = "notion.so")
        val step = presenter.step("mcp_notion_notion-create-pages", args("""{"toolTitle":"spoofed"}"""), origin)
        assertEquals("Create pages in Notion", step.running)
        assertEquals("notion.so", step.site)

        val card = presenter.decision("mcp_notion_notion-create-pages", args("{}"), origin)
        assertEquals("Create pages in Notion?", card.title)
        assertEquals("notion.so", card.site)
    }

    @Test
    fun `a connected tool without its names falls back instead of showing a template`() {
        assertEquals("Working", presenter.step("mcp_notion_search", args("{}")).running)
        assertEquals("Allow this action?", presenter.decision("mcp_notion_search", args("{}")).title)
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
}
