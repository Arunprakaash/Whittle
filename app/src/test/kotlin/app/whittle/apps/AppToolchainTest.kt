package app.whittle.apps

import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigLoader
import app.whittle.tools.workspace.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppToolchainTest {
    private val config = assertIs<Outcome.Success<NativeAppsConfig>>(
        ConfigLoader(BundledConfigSource()).load(NativeAppsConfig.FILE, NativeAppsConfig.serializer(), NativeAppsConfig.SCHEMA_VERSION),
    ).value

    private fun result(exit: Int?, out: String = "", err: String = "") = Outcome.Success(CommandResult(exit, out, err, timedOut = exit == null, truncated = false, durationMillis = 1))

    private class FakeShell(val onRun: suspend (String, Boolean, Int) -> Outcome<CommandResult>) : LinuxShell {
        val calls = mutableListOf<Triple<String, Boolean, Int>>()
        override suspend fun run(command: String, network: Boolean, timeoutSeconds: Int): Outcome<CommandResult> {
            calls += Triple(command, network, timeoutSeconds)
            return onRun(command, network, timeoutSeconds)
        }
    }

    @Test
    fun `ready only when the marker holds the pins hash`() = runBlocking<Unit> {
        val hash = AppToolchain.pinsHash(config)
        assertEquals(64, hash.length)
        val current = FakeShell { _, _, _ -> result(0, "$hash\n") }
        assertTrue(AppToolchain(current, config).isReady())
        assertFalse(current.calls.single().second, "the check runs without network")
        assertEquals("cat /opt/whittle-apps/ready", current.calls.single().first)

        assertFalse(AppToolchain(FakeShell { _, _, _ -> result(0, "0ldhash\n") }, config).isReady())
        assertFalse(AppToolchain(FakeShell { _, _, _ -> result(1, "", "No such file") }, config).isReady())
    }

    @Test
    fun `install runs the setup script with network on and reports Ready`() = runBlocking<Unit> {
        val hash = AppToolchain.pinsHash(config)
        var installed = false
        val shell = FakeShell { command, _, _ ->
            if (command.startsWith("cat ")) {
                if (installed) result(0, hash) else result(1)
            } else {
                installed = true
                result(0)
            }
        }
        val toolchain = AppToolchain(shell, config)
        assertEquals(ToolchainState.NotInstalled, toolchain.state.value)
        assertIs<Outcome.Success<Unit>>(toolchain.install())
        val setup = shell.calls.first { !it.first.startsWith("cat ") }
        assertTrue(setup.second)
        assertEquals(config.toolchain.setupTimeoutSeconds, setup.third)
        assertFalse(setup.first.contains("{{pinsHash}}"))
        assertTrue(config.toolchain.setupScript.contains("{{pinsHash}}"), "the bundled script has the placeholder")
        assertTrue(setup.first.contains(hash))
        assertEquals(ToolchainState.Ready, toolchain.state.value)
    }

    @Test
    fun `a failed setup leaves it not ready and the next install runs again`() = runBlocking<Unit> {
        val hash = AppToolchain.pinsHash(config)
        var attempts = 0
        var installed = false
        val shell = FakeShell { command, _, _ ->
            when {
                command.startsWith("cat ") -> if (installed) result(0, hash) else result(1)
                ++attempts == 1 -> result(2, "", "E: Unable to locate package\n")
                else -> { installed = true; result(0) }
            }
        }
        val toolchain = AppToolchain(shell, config)
        assertIs<Outcome.Failure>(toolchain.install())
        val failed = assertIs<ToolchainState.Failed>(toolchain.state.value)
        assertTrue(failed.reason.contains("Unable to locate package"))
        assertFalse(toolchain.isReady())
        assertIs<Outcome.Success<Unit>>(toolchain.install())
        assertEquals(2, attempts)
        assertEquals(ToolchainState.Ready, toolchain.state.value)
    }

    @Test
    fun `a setup that exits cleanly without writing the marker is a failure`() = runBlocking<Unit> {
        val shell = FakeShell { command, _, _ -> if (command.startsWith("cat ")) result(1) else result(0) }
        val toolchain = AppToolchain(shell, config)
        assertIs<Outcome.Failure>(toolchain.install())
        assertIs<ToolchainState.Failed>(toolchain.state.value)
    }

    private val wait = 300.milliseconds

    @Test
    fun `awaitSetup is false when starting throws`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { _, _, _ -> result(1) }, config)
        assertFalse(awaitSetup(toolchain, { throw IllegalStateException("not allowed") }, wait))
    }

    @Test
    fun `awaitSetup is true when setup never starts but the marker is good`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { _, _, _ -> result(0, AppToolchain.pinsHash(config)) }, config)
        assertTrue(awaitSetup(toolchain, {}, 50.milliseconds))
    }

    @Test
    fun `awaitSetup is false when setup never starts and there is no marker`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { _, _, _ -> result(1) }, config)
        assertFalse(awaitSetup(toolchain, {}, 50.milliseconds))
    }

    @Test
    fun `awaitSetup is true when it goes Installing then Ready`() = runBlocking<Unit> {
        val hash = AppToolchain.pinsHash(config)
        var installed = false
        val toolchain = AppToolchain(
            FakeShell { command, _, _ ->
                if (command.startsWith("cat ")) {
                    if (installed) result(0, hash) else result(1)
                } else {
                    delay(100)
                    installed = true
                    result(0)
                }
            },
            config,
        )
        assertTrue(awaitSetup(toolchain, { CoroutineScope(Dispatchers.Default).launch { toolchain.install() } }, wait))
    }

    @Test
    fun `awaitSetup is false when it goes Installing then Failed`() = runBlocking<Unit> {
        val toolchain = AppToolchain(
            FakeShell { command, _, _ -> if (command.startsWith("cat ")) result(1) else { delay(100); result(2, "", "boom") } },
            config,
        )
        assertFalse(awaitSetup(toolchain, { CoroutineScope(Dispatchers.Default).launch { toolchain.install() } }, wait))
    }

    @Test
    fun `awaitSetup is false when the install is cancelled`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { command, _, _ -> if (command.startsWith("cat ")) result(1) else awaitCancellation() }, config)
        val scope = CoroutineScope(Dispatchers.Default)
        val install = scope.launch { toolchain.install() }
        val cancelling = scope.launch { toolchain.state.first { it is ToolchainState.Installing }; install.cancel() }
        assertFalse(awaitSetup(toolchain, {}, wait))
        cancelling.join()
        assertIs<ToolchainState.Failed>(toolchain.state.value)
    }

    @Test
    fun `isReady moves Ready to NotInstalled when the marker is gone`() = runBlocking<Unit> {
        var markerGood = false
        val toolchain = readyThenGone { markerGood = it }
        assertEquals(ToolchainState.Ready, toolchain.state.value)
        markerGood = false
        assertFalse(toolchain.isReady())
        assertEquals(ToolchainState.NotInstalled, toolchain.state.value)
    }

    @Test
    fun `awaitSetup is false after Ready when the marker has since gone and setup never starts`() = runBlocking<Unit> {
        val toolchain = readyThenGone { }
        assertEquals(ToolchainState.Ready, toolchain.state.value)
        assertFalse(toolchain.isReady())
        assertFalse(awaitSetup(toolchain, {}, 50.milliseconds))
    }

    /** A toolchain driven through a real install; [setMarker] is called with false afterwards by tests via the shared flag. */
    private fun readyThenGone(setMarker: (Boolean) -> Unit): AppToolchain = runBlocking {
        val hash = AppToolchain.pinsHash(config)
        var present = false
        val toolchain = AppToolchain(
            FakeShell { command, _, _ -> if (command.startsWith("cat ")) (if (present) result(0, hash) else result(1)) else { present = true; result(0) } },
            config,
        )
        toolchain.install()
        // The marker disappears after setup (Linux was reset, say).
        present = false
        setMarker(false)
        toolchain
    }
}
