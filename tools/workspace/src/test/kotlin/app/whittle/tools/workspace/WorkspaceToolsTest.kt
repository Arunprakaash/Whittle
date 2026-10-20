package app.whittle.tools.workspace

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceToolsTest {
    private val root = Files.createTempDirectory("whittle-workspaces").toFile()
    private val workspaces = Workspaces(root)
    private val config = TerminalConfig(TerminalConfig.SCHEMA_VERSION, defaultTimeoutSeconds = 5, maxTimeoutSeconds = 10, maxOutputChars = 2_000, maxFileChars = 1_000, maxListedEntries = 50)
    // The desktop shell has no setsid; Android's environment adds process groups.
    private val environment = ShellEnvironment(shell = "/bin/sh", path = "/usr/bin:/bin", processGroupLauncher = null, killProcessGroup = null)
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"), WorkspaceId("chat-1"))

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `commands start in the workspace with a scrubbed environment`() = runBlocking<Unit> {
        val output = command("""pwd -P; echo "${'$'}HOME"; env | grep -q '^USER=' && echo leaked || echo clean""")
        val lines = output.field("stdout").lines()
        assertEquals(File(root, "chat-1").canonicalPath, lines[0])
        assertEquals(File(root, "chat-1").path, lines[1])
        assertEquals("clean", lines[2], "the app's own environment must not leak into commands")
    }

    @Test
    fun `a failing command reports its exit code as an error`() = runBlocking<Unit> {
        val tool = RunCommandTool(workspaces, CommandRunner(environment, config.maxOutputChars), config)
        val output = tool.execute(args("""{"command":"echo nope >&2; exit 3"}"""), context)
        assertTrue(output.isError)
        assertEquals("3", output.json()["exitCode"]!!.jsonPrimitive.content)
        assertEquals("nope\n", output.json()["stderr"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a command past its timeout is killed`() = runBlocking<Unit> {
        val started = System.currentTimeMillis()
        val output = command("""sleep 5""", timeout = 1)
        assertEquals("1", output.field("timedOutAfterSeconds"))
        assertTrue(System.currentTimeMillis() - started < 4_000)
    }

    @Test
    fun `cancelling a run stops its command at once`() = runBlocking<Unit> {
        val runner = CommandRunner(environment, config.maxOutputChars)
        val directory = File(root, "chat-1").apply { mkdirs() }
        val job = launch(Dispatchers.IO) { runner.run("sleep 30", directory, timeoutSeconds = 60) }
        delay(500)
        val started = System.currentTimeMillis()
        job.cancelAndJoin()
        assertTrue(System.currentTimeMillis() - started < 3_000, "Stop must not wait for the command to finish")
    }

    @Test
    fun `long output is capped`() = runBlocking<Unit> {
        val output = command("seq 1 1000")
        assertEquals("true", output.field("truncated"))
        assertTrue(output.field("stdout").length <= config.maxOutputChars)
    }

    @Test
    fun `a background job does not hold the command open`() = runBlocking<Unit> {
        val started = System.currentTimeMillis()
        val output = command("sleep 30 & echo started")
        assertEquals("started\n", output.field("stdout"))
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun `files are written, read, listed and edited inside the workspace`() = runBlocking<Unit> {
        WriteFileTool(workspaces, config).execute(args("""{"path":"notes/todo.txt","content":"buy milk\ncall Priya\n"}"""), context)
        EditFileTool(workspaces, config).execute(args("""{"path":"notes/todo.txt","oldText":"call Priya","newText":"text Priya"}"""), context)

        val read = ReadFileTool(workspaces, config).execute(args("""{"path":"notes/todo.txt"}"""), context)
        assertEquals("buy milk\ntext Priya\n", read.json()["content"]!!.jsonPrimitive.content)
        val listed = ListFilesTool(workspaces, config).execute(args("""{}"""), context)
        assertTrue(listed.content.contains("\"name\":\"notes\""))
    }

    @Test
    fun `an edit must match exactly once`() = runBlocking<Unit> {
        WriteFileTool(workspaces, config).execute(args("""{"path":"a.txt","content":"x x"}"""), context)
        val output = EditFileTool(workspaces, config).execute(args("""{"path":"a.txt","oldText":"x","newText":"y"}"""), context)
        assertTrue(output.isError)
        assertTrue(output.content.contains("2 times"))
    }

    @Test
    fun `paths outside the workspace are refused, through symlinks too`() = runBlocking<Unit> {
        val outside = Files.createTempFile("outside", ".txt").toFile().apply { writeText("secret") }
        val workspace = File(root, "chat-1").apply { mkdirs() }
        Files.createSymbolicLink(File(workspace, "link.txt").toPath(), outside.toPath())
        val read = ReadFileTool(workspaces, config)
        listOf("../escape.txt", "/etc/hosts", "link.txt").forEach { path ->
            val output = read.execute(args("""{"path":"$path"}"""), context)
            assertTrue(output.isError, "$path should be refused")
            assertFalse(output.content.contains("secret"))
        }
        outside.delete()
    }

    @Test
    fun `tools refuse to run without a workspace`() = runBlocking<Unit> {
        val output = ListFilesTool(workspaces, config).execute(args("{}"), ToolExecutionContext(RunId("run"), ToolCallId("call")))
        assertTrue(output.isError)
    }

    private suspend fun command(line: String, timeout: Int? = null): app.whittle.tools.api.ToolOutput {
        val tool = RunCommandTool(workspaces, CommandRunner(environment, config.maxOutputChars), config)
        val timeoutField = timeout?.let { ""","timeoutSeconds":$it""" }.orEmpty()
        return tool.execute(args("""{"command":${Json.encodeToString(String.serializer(), line)}$timeoutField}"""), context)
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun app.whittle.tools.api.ToolOutput.json(): JsonObject = Json.parseToJsonElement(content).jsonObject

    private fun app.whittle.tools.api.ToolOutput.field(name: String): String = json()[name]!!.jsonPrimitive.content
}
