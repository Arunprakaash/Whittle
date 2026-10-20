package app.whittle.runtimes

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whittle.BuildConfig
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigLoader
import app.whittle.settings.AppDefaults
import app.whittle.tools.workspace.CommandResult
import app.whittle.tools.workspace.CommandRunner
import app.whittle.tools.workspace.ShellEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * What the command sandbox actually stops, in the app's own process and SELinux domain. Commands are
 * offline unless allowed the network, and can't look into Whittle's memory either way.
 */
@RunWith(AndroidJUnit4::class)
class CommandSandboxTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val defaults = assertIs<Outcome.Success<AppDefaults>>(
        ConfigLoader(BundledConfigSource()).load(AppDefaults.FILE, AppDefaults.serializer(), AppDefaults.SCHEMA_VERSION),
    ).value
    private val workspace = File(context.filesDir, "command-sandbox-test").apply { mkdirs() }
    private val runner = CommandRunner(
        ShellEnvironment(
            shell = defaults.terminal.shell,
            path = defaults.terminal.path,
            launcher = CommandSandbox.launcher(context),
            killProcessGroup = null,
            tools = ToolRuntime(context, defaults.toolRuntime, defaults.terminal.path, BuildConfig.RUNTIME_ASSETS),
        ),
        maxOutputChars = 20_000,
    )

    /** Whittle's own process: what a command must not be able to read. */
    private val appPid = Process.myPid()

    @After
    fun cleanUp() {
        workspace.deleteRecursively()
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
        // Android resolves names through a local socket; that would otherwise carry data out.
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
    fun networkCommandsCanOpenSockets() = runBlocking<Unit> {
        val script = """
            import socket
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            print("listening")
        """.trimIndent()
        assertEquals("listening", python(script, network = true).stdout.trim())
    }

    @Test
    fun commandsCantTraceOrReadWhittlesMemory() = runBlocking<Unit> {
        val script = """
            import ctypes, os
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

    private suspend fun python(script: String, network: Boolean = false): CommandResult {
        File(workspace, "probe.py").writeText(script)
        return run("python3 probe.py", network)
    }

    private suspend fun run(command: String, network: Boolean = false): CommandResult =
        runner.run(command, workspace, TIMEOUT_SECONDS, network)

    private companion object {
        const val TIMEOUT_SECONDS = 60
    }
}
