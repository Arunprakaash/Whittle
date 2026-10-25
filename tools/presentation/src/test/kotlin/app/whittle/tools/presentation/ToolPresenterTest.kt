package app.whittle.tools.presentation

import app.whittle.tools.api.CallPreview
import app.whittle.tools.api.ToolOrigin
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                "delete_files" to ToolPresentation(
                    "delete",
                    "Deleting files",
                    "Done",
                    decision.copy(title = "Delete these files for good?", body = "{{total}} in all.", payloadArgument = "listing", payloadLabelArgument = null),
                    previewed = listOf("listing", "count", "total"),
                ),
                "delete_files.unchecked" to ToolPresentation(
                    "delete",
                    "Deleting files",
                    "Done",
                    decision.copy(title = "Whittle couldn't check these files", body = "Nothing will be deleted.", payloadArgument = "listing", payloadLabelArgument = null),
                    previewed = listOf("listing", "count", "total"),
                ),
                "set_alarm" to ToolPresentation("alarm", "Setting an alarm", "Set an alarm", decision.copy(title = "Set an alarm?", payloadArgument = "label", payloadLabelArgument = null)),
                "delete_files.nothing" to ToolPresentation(
                    "delete",
                    "Deleting files",
                    "Done",
                    decision.copy(title = "Nothing here can be deleted", body = "", payloadArgument = "listing", payloadLabelArgument = null),
                    previewed = listOf("listing", "count", "total"),
                ),
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

    @Test
    fun `a delete card shows what the tool found, and no argument can change it`() {
        val values = mapOf("listing" to "/sdcard/a.jpg  ·  1.5 KB\n/sdcard/b.pdf  ·  2.5 KB", "count" to "2", "total" to "4.0 KB")
        val preview = CallPreview(values, approved = listOf("/storage/a.jpg", "/storage/b.pdf"))
        val card = presenter.decision("delete_files", args("""{"paths":["/sdcard/a.jpg","/sdcard/b.pdf"],"listing":"nothing much","count":"1","total":"1 B"}"""), preview = preview)
        assertEquals("Delete these files for good?", card.title)
        assertEquals("4.0 KB in all.", card.body)
        assertEquals(values["listing"], card.payload)
    }

    @Test
    fun `without a preview, arguments can't fill what the tool fills, and the card shows the exact call`() {
        val card = presenter.decision("delete_files", args("""{"paths":["/sdcard/a.jpg"],"listing":"/sdcard/x.jpg  ·  1 B","total":"1 B"}"""))
        assertEquals("paths: [\"/sdcard/a.jpg\"]", card.payload)
        assertTrue("1 B" !in card.title + card.body + card.payload)
    }

    @Test
    fun `a tool that fills its card from a preview but got none says nothing will be deleted`() {
        val card = presenter.decision("delete_files", args("""{"paths":["/sdcard/a.jpg"]}"""))
        assertEquals("Whittle couldn't check these files", card.title)
        assertEquals("paths: [\"/sdcard/a.jpg\"]", card.payload)
        // With a preview, the tool's own card stays.
        val preview = CallPreview(mapOf("listing" to "x", "count" to "1", "total" to "1 B"), approved = listOf("/a"))
        assertEquals("Delete these files for good?", presenter.decision("delete_files", args("{}"), preview = preview).title)
    }

    @Test
    fun `a card whose payload is missing doesn't show the raw arguments unless the tool fills it itself`() {
        assertNull(presenter.decision("set_alarm", args("""{"time":"07:30"}""")).payload)
    }

    @Test
    fun `a preview can pick the tool's card for when nothing will happen`() {
        val preview = CallPreview(mapOf("listing" to "/sdcard/gone.jpg  ·  won't be deleted: doesn't exist", "count" to "0", "total" to "0 B"), emptyList(), variant = "nothing")
        val card = presenter.decision("delete_files", args("""{"paths":["/sdcard/gone.jpg"]}"""), preview = preview)
        assertEquals("Nothing here can be deleted", card.title)
        assertEquals(preview.card["listing"], card.payload)
        // An unknown variant keeps the tool's own card.
        assertEquals("Delete these files for good?", presenter.decision("delete_files", args("{}"), preview = preview.copy(variant = "other")).title)
    }

    @Test
    fun `an argument can't pose as the site a page is read from`() {
        listOf(
            """{"url":"https://example.com/a","url_host":"bank.com"}""",
            """{"url_host":"bank.com","url":"https://example.com/a"}""",
            """{"url":"https://example.com/a","url_host":["bank.com"]}""",
        ).forEach { json ->
            val step = presenter.step("web_fetch", args(json))
            assertEquals("Reading example.com", step.running, json)
            assertEquals("example.com", step.site, json)
        }
        assertEquals("Working", presenter.step("web_fetch", args("""{"url_host":"bank.com"}""")).running)
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
}
