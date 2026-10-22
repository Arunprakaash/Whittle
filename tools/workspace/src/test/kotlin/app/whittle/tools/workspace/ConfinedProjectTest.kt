package app.whittle.tools.workspace

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A chat an automation started only reaches its project, and can't move itself to another one. */
class ConfinedProjectTest {
    private val root = Files.createTempDirectory("whittle-confined").toFile()
    private val chat = WorkspaceId("chat-1")
    private val projects = Projects(project = "brief", confined = true)
    private val workspaces = Workspaces(root, projects = projects)
    private val config = TerminalConfig(TerminalConfig.SCHEMA_VERSION, defaultTimeoutSeconds = 5, maxTimeoutSeconds = 10, maxOutputChars = 2_000, maxFileChars = 1_000, maxListedEntries = 50, processes = ProcessLimits(maxPerChat = 2, startWaitSeconds = 10, noPortWaitSeconds = 2, keptOutputChars = 4_000, readLines = 20, stopGraceMillis = 2_000))
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"), chat)

    init {
        File(root, "brief").mkdirs()
        File(root, "other").mkdirs()
        File(root, "other/notes.md").writeText("keep\n")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a confined chat works inside its project`() = runBlocking<Unit> {
        val written = WriteFileTool(workspaces, config).execute(args("""{"path":"today.md","content":"hello\n"}"""), context)
        assertFalse(written.isError, written.content)
        assertEquals("hello\n", File(root, "brief/today.md").readText())
    }

    @Test
    fun `a confined chat can't reach another project`() = runBlocking<Unit> {
        val edited = WriteFileTool(workspaces, config).execute(args("""{"path":"../other/notes.md","content":"gone\n"}"""), context)
        assertTrue(edited.isError)
        assertEquals("keep\n", File(root, "other/notes.md").readText())
        assertTrue(ReadFileTool(workspaces, config).execute(args("""{"path":"../other/notes.md"}"""), context).isError)
    }

    @Test
    fun `an unconfined chat still reaches the whole workspace`() = runBlocking<Unit> {
        projects.confined = false
        val read = ReadFileTool(workspaces, config).execute(args("""{"path":"../other/notes.md"}"""), context)
        assertFalse(read.isError, read.content)
    }

    @Test
    fun `a confined chat can't change its own project`() = runBlocking<Unit> {
        val moved = UseProjectTool(workspaces, projects).execute(args("""{"name":"other"}"""), context)
        assertTrue(moved.isError)
        assertEquals("brief", projects.projectOf(chat))
        assertTrue(UseProjectTool(workspaces, projects).execute(args("{}"), context).isError)
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private class Projects(var project: String?, var confined: Boolean) : ChatProjects {
        override fun projectOf(chat: WorkspaceId): String? = project

        override fun choose(chat: WorkspaceId, project: String?) {
            this.project = project
        }

        override fun isConfined(chat: WorkspaceId): Boolean = confined
    }
}
