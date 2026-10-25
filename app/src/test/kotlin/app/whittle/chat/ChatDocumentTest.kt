package app.whittle.chat

import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.presentation.CapabilityPresentation
import app.whittle.tools.presentation.DecisionText
import app.whittle.tools.presentation.QuestionText
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatDocumentTest {
    private val run = RunId("run-1")

    @Test
    fun `an app card saved with the old lastCrash field still loads`() {
        val saved = """{"schemaVersion":${ChatDocument.SCHEMA_VERSION},"title":"Notes","items":[{"type":"app","key":"a1","name":"Notes",""" +
            """"packageName":"app.whittle.made.notes","versionCode":2,"apkPath":"notes/build/notes-2.apk","permissions":[],""" +
            """"powerful":[],"fromTaintedRun":false,"lastCrash":"boom"}],"history":[],"activeRunId":null,"nextKey":2}"""
        val card = ChatDocument.decode(saved)?.items?.single()
        assertEquals(ThreadItem.App("a1", "Notes", "app.whittle.made.notes", 2, "notes/build/notes-2.apk", emptyList(), emptyList()), card)
    }

    @Test
    fun `every kind of thread item survives a save and load`() {
        val narration = ThreadItem.Narration("n1", "Let me check.", streaming = false)
        val steps = ThreadItem.Steps("s1", listOf(StepModel("c1", "schedule", "Checked the time", StepState.Done)))
        val document = ChatDocument(
            schemaVersion = ChatDocument.SCHEMA_VERSION,
            title = "Running late",
            items = listOf(
                ThreadItem.User("u1", "Running late", steer = false),
                ThreadItem.Summary("sum1", stepCount = 1, seconds = 4, expanded = false, folded = listOf(narration, steps)),
                ThreadItem.Decision("d1", run, ToolCallId("c2"), DecisionText("sms", "Send?", "Opens Messages.", "Hi", "Priya", "Open", "open_in_new", "Not now", "Opened", "Not sent")),
                ThreadItem.Question("q1", run, ToolCallId("c3"), QuestionText("help", "Which Priya?", listOf("Priya S", "Priya K"))),
                ThreadItem.Capability("cap1", run, CapabilityId("contacts"), CapabilityPresentation("contacts", "Allow?", "So it can.", "Allow", "Not now", "Allowed", "Not allowed")),
                ThreadItem.Resolved("r1", "Opened", declined = false),
                ThreadItem.Notice("x1", NoticeKind.FailedProvider, statusCode = 500),
                ThreadItem.Interrupted("i1", run),
            ),
            history = listOf(Message.userText("Running late")),
            activeRunId = run,
            nextKey = 12,
        )
        val json = Json { ignoreUnknownKeys = true }

        val decoded = json.decodeFromString(ChatDocument.serializer(), json.encodeToString(ChatDocument.serializer(), document))

        assertEquals(document, decoded)
    }
}
