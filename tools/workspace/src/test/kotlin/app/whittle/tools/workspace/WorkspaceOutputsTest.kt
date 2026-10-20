package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkspaceOutputsTest {
    private val root = Files.createTempDirectory("workspaces").toFile()
    private val outputs = WorkspaceOutputs(Workspaces(root), ".whittle/outputs")

    @Test
    fun `a tool's JSON report is saved with its text as real lines, so grep and sed can read it`() = runTest {
        val saved = outputs.save(WorkspaceId("chat1"), RunId("run 1"), ToolCallId("call/7"), """{"exitCode":0,"stdout":"2\n3\n5","stderr":""}""")

        val path = assertIs<Outcome.Success<String>>(saved).value
        assertEquals(".whittle/outputs/run_1-call_7.txt", path)
        assertEquals("[exitCode] 0\n\n[stdout]\n2\n3\n5\n\n[stderr]\n", File(root, "chat1/$path").readText())
    }

    @Test
    fun `anything that isn't a JSON object is saved as it is`() = runTest {
        val path = assertIs<Outcome.Success<String>>(outputs.save(WorkspaceId("chat1"), RunId("r"), ToolCallId("c"), "plain\ntext")).value

        assertEquals("plain\ntext", File(root, "chat1/$path").readText())
    }
}
