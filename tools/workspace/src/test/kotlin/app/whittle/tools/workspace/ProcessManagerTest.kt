package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessManagerTest {
    private val root = Files.createTempDirectory("whittle-processes").toFile()
    private val workspaces = Workspaces(root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limits = ProcessLimits(maxPerChat = 2, startWaitSeconds = 10, noPortWaitSeconds = 2, keptOutputChars = 4_000, readLines = 20, stopGraceMillis = 2_000)
    private val environment = ShellEnvironment(shell = "/bin/sh", path = "/usr/bin:/bin", launcher = null, killProcessGroup = null)
    private val manager = ProcessManager(environment, limits, scope)
    private val chat = WorkspaceId("chat-1")
    private val directory = (workspaces.directory(chat) as Outcome.Success).value

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        manager.stopAll()
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun `a server is live once its port answers, and stops with everything it started`() = runBlocking<Unit> {
        val port = freePort()
        val info = started("site", server(port), port)
        assertTrue(info.listening)
        assertEquals("http://127.0.0.1:$port/", info.url)
        val stopped = manager.stop(info.id)!!
        assertEquals(ProcessStatus.Stopped, stopped.status)
        assertFalse(answers(port), "the server must be gone after a stop")
    }

    @Test
    fun `a port in the output is found when none was given`() = runBlocking<Unit> {
        val port = freePort()
        val info = started("site", "echo 'Local: http://localhost:$port/'; exec ${server(port)}", port = null)
        assertEquals(port, info.port)
        assertTrue(info.listening)
    }

    @Test
    fun `a command that ends at once reports its exit code and output`() = runBlocking<Unit> {
        val info = started("broken", "echo 'Error: Cannot find module vite'; exit 3", port = null)
        assertEquals(ProcessStatus.Exited, info.status)
        assertEquals(3, info.exitCode)
        assertTrue(manager.output(info.id)!!.contains("Cannot find module vite"))
    }

    @Test
    fun `starting the same name again replaces it, and a chat has a limit`() = runBlocking<Unit> {
        val first = started("watch", "sleep 30", port = null)
        val second = started("watch", "sleep 30", port = null)
        assertNull(manager.info(first.id), "the first one is replaced")
        assertTrue(second.live)
        started("other", "sleep 30", port = null)
        assertIs<Outcome.Failure>(manager.start(chat, directory, "third", "sleep 30", port = null))
    }

    @Test
    fun `the tools only see their own chat's processes`() = runBlocking<Unit> {
        val info = started("site", "sleep 30", port = null)
        val otherChat = ToolExecutionContext(RunId("run"), ToolCallId("call"), WorkspaceId("chat-2"))
        val read = ReadProcessOutputTool(workspaces, manager).execute(args("""{"id":"${info.id}"}"""), otherChat)
        assertTrue(read.isError)
        val stop = StopProcessTool(workspaces, manager).execute(args("""{"id":"${info.id}"}"""), otherChat)
        assertTrue(stop.isError)
        assertTrue(manager.info(info.id)!!.live)
    }

    @Test
    fun `start_process reports a server's address and fails a command that ended`() = runBlocking<Unit> {
        val tool = StartProcessTool(workspaces, manager)
        val context = ToolExecutionContext(RunId("run"), ToolCallId("call"), chat)
        val port = freePort()
        val ok = tool.execute(args("""{"name":"site","command":"${server(port)}","port":$port}"""), context)
        assertFalse(ok.isError)
        assertEquals("http://127.0.0.1:$port/", Json.parseToJsonElement(ok.content).jsonObject["url"]!!.jsonPrimitive.content)
        val failed = tool.execute(args("""{"name":"broken","command":"exit 2"}"""), context)
        assertTrue(failed.isError)
    }

    private suspend fun started(name: String, command: String, port: Int?): ProcessInfo =
        assertIs<Outcome.Success<ProcessInfo>>(manager.start(chat, directory, name, command, port)).value

    private fun server(port: Int) = "python3 -m http.server $port --bind 127.0.0.1"

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun answers(port: Int): Boolean = try {
        java.net.Socket("127.0.0.1", port).close()
        true
    } catch (exception: java.io.IOException) {
        false
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
}
