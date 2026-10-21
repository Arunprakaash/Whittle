package app.whittle.linux

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whittle.WhittleApplication
import app.whittle.core.common.Outcome
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.CommandResult
import app.whittle.tools.workspace.CommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Linux as commands get it, in the app's own process and SELinux domain: set up from the real image, commands in
 * /workspace behind the sandbox (offline unless allowed the network, unable to look into Whittle), and the file tools
 * seeing the same files. The first run downloads and sets up Linux, so it needs the network and a few minutes.
 */
@RunWith(AndroidJUnit4::class)
class LinuxWorkspaceTest {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WhittleApplication).graph
    private val linux = graph.linux
    private val id = WorkspaceId("linux-workspace-test")
    private val workspace = assertIs<Outcome.Success<File>>(graph.workspaces.directory(id)).value
    private val runner = CommandRunner(linux.environment, maxOutputChars = 20_000)

    /** Whittle's own process: what a command must not be able to read. */
    private val appPid = Process.myPid()

    @Before
    fun setUpLinux() = runBlocking<Unit> {
        if (linux.state.value !is LinuxState.Ready) assertIs<Outcome.Success<Unit>>(linux.setUp(), "setup: ${linux.state.value}")
        // The probes need Python with ctypes, which python3-minimal lacks.
        val python = run("python3 -c 'import ctypes' 2>/dev/null || apt-get install -y -q --no-install-recommends python3", network = true, timeoutSeconds = SETUP_SECONDS)
        assertEquals(0, python.exitCode, python.stderr)
    }

    @After
    fun cleanUp() {
        graph.workspaces.delete(id)
    }

    @Test
    fun commandsRunInUbuntuInTheWorkspace() = runBlocking<Unit> {
        val lines = run("""pwd; . /etc/os-release; echo "${'$'}ID"; echo "${'$'}HOME"""").stdout.trim().lines()
        assertEquals(listOf("/workspace", "ubuntu", "/root"), lines)
    }

    @Test
    fun theAppsEnvironmentDoesntReachCommands() = runBlocking<Unit> {
        val environment = run("env").stdout
        listOf("PROOT_", "ANDROID_", "WHITTLE_COMMAND").forEach { name ->
            assertFalse(environment.lines().any { it.startsWith(name) }, "$name must not reach commands")
        }
    }

    @Test
    fun offlineCommandsCantOpenSockets() = runBlocking<Unit> {
        val script = """
            import socket
            try:
                socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                print("opened")
            except PermissionError:
                print("blocked")
        """.trimIndent()
        assertEquals("blocked", python(script).stdout.trim())
    }

    @Test
    fun offlineCommandsCantLookUpNames() = runBlocking<Unit> {
        val script = """
            import socket
            try:
                socket.getaddrinfo("example.com", 80)
                print("resolved")
            except OSError:
                print("blocked")
        """.trimIndent()
        assertEquals("blocked", python(script).stdout.trim())
    }

    @Test
    fun networkCommandsCanOpenSocketsAndResolveNames() = runBlocking<Unit> {
        val script = """
            import socket
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            socket.getaddrinfo("example.com", 443)
            print("ok")
        """.trimIndent()
        assertEquals("ok", python(script, network = true).stdout.trim())
    }

    @Test
    fun commandsCantTraceOrReadWhittlesMemory() = runBlocking<Unit> {
        val script = """
            import ctypes
            libc = ctypes.CDLL(None, use_errno=True)
            PTRACE_ATTACH = 16
            libc.ptrace.argtypes = [ctypes.c_long, ctypes.c_long, ctypes.c_void_p, ctypes.c_void_p]
            traced = libc.ptrace(PTRACE_ATTACH, $appPid, None, None)
            print("ptrace", traced, ctypes.get_errno())
            buffer = ctypes.create_string_buffer(8)
            class IoVec(ctypes.Structure):
                _fields_ = [("base", ctypes.c_void_p), ("length", ctypes.c_size_t)]
            local = IoVec(ctypes.cast(buffer, ctypes.c_void_p), 8)
            remote = IoVec(ctypes.c_void_p(0x1000), 8)
            libc.process_vm_readv.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong]
            read = libc.process_vm_readv($appPid, ctypes.byref(local), 1, ctypes.byref(remote), 1, 0)
            print("vm_readv", read, ctypes.get_errno())
        """.trimIndent()
        val lines = python(script).stdout.trim().lines()
        assertEquals("ptrace -1 1", lines[0], "ptrace on Whittle must fail with EPERM")
        assertEquals("vm_readv -1 1", lines[1], "process_vm_readv on Whittle must fail with EPERM")
    }

    @Test
    fun commandsCantOpenWhittlesMemoryFile() = runBlocking<Unit> {
        val result = run("cat /proc/$appPid/mem > /dev/null 2>&1 && echo readable || echo refused")
        assertNotEquals("readable", result.stdout.trim(), "Whittle's /proc/<pid>/mem must not be readable from a command")
    }

    @Test
    fun fileToolsSeeWhatCommandsSee() = runBlocking<Unit> {
        val note = assertIs<Outcome.Success<File>>(graph.workspaces.locate(id, "notes/hello.txt")).value
        note.parentFile?.mkdirs()
        note.writeText("from the file tools")
        assertEquals("from the file tools", run("cat /workspace/notes/hello.txt").stdout.trim())

        run("echo from a command > /etc/whittle-test")
        val written = assertIs<Outcome.Success<File>>(graph.workspaces.locate(id, "/etc/whittle-test")).value
        assertEquals("from a command", written.readText().trim())
        run("rm /etc/whittle-test")

        // /bin is a link to usr/bin in Ubuntu; the file tools follow it inside Linux.
        assertTrue(assertIs<Outcome.Success<File>>(graph.workspaces.locate(id, "/bin/bash")).value.isFile)
        assertIs<Outcome.Failure>(graph.workspaces.locate(id, "/proc/self/status"))
    }

    private suspend fun python(script: String, network: Boolean = false): CommandResult {
        File(workspace, "probe.py").writeText(script)
        return run("python3 probe.py", network)
    }

    private suspend fun run(command: String, network: Boolean = false, timeoutSeconds: Int = TIMEOUT_SECONDS): CommandResult =
        assertIs<Outcome.Success<CommandResult>>(runner.run(command, workspace, timeoutSeconds, network)).value

    private companion object {
        const val TIMEOUT_SECONDS = 60
        const val SETUP_SECONDS = 600
    }
}
