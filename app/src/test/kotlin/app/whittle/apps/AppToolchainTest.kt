package app.whittle.apps

import app.whittle.linux.ui.Toolchain
import app.whittle.agent.AgentMessages
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.tools.workspace.CommandResult
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
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
        assertEquals(ToolchainFailure.Output, failed.kind)
        assertTrue(failed.detail.orEmpty().contains("Unable to locate package"))
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
        assertEquals(ToolchainState.Failed(ToolchainFailure.NotConfirmed), toolchain.state.value)
    }

    @Test
    fun `a setup that runs out of time says so, with no detail`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { command, _, _ -> if (command.startsWith("cat ")) result(1) else result(null) }, config)
        assertIs<Outcome.Failure>(toolchain.install())
        assertEquals(ToolchainState.Failed(ToolchainFailure.TimedOut), toolchain.state.value)
    }

    private val wait = 300.milliseconds

    @Test
    fun `awaitSetup is false when starting throws`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { _, _, _ -> result(1) }, config)
        assertEquals(SetupOutcome.Failed(ToolchainFailure.NotStarted, "not allowed"), awaitSetup(toolchain, { throw IllegalStateException("not allowed") }, wait))
    }

    @Test
    fun `awaitSetup is true when setup never starts but the marker is good`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { _, _, _ -> result(0, AppToolchain.pinsHash(config)) }, config)
        assertEquals(SetupOutcome.Ready, awaitSetup(toolchain, {}, 50.milliseconds))
    }

    @Test
    fun `awaitSetup is false when setup never starts and there is no marker`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { _, _, _ -> result(1) }, config)
        assertEquals(SetupOutcome.Failed(ToolchainFailure.NotStarted), awaitSetup(toolchain, {}, 50.milliseconds))
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
        assertEquals(SetupOutcome.Ready, awaitSetup(toolchain, { CoroutineScope(Dispatchers.Default).launch { toolchain.install() } }, wait))
    }

    @Test
    fun `awaitSetup is false when it goes Installing then Failed`() = runBlocking<Unit> {
        val toolchain = AppToolchain(
            FakeShell { command, _, _ -> if (command.startsWith("cat ")) result(1) else { delay(100); result(2, "", "boom") } },
            config,
        )
        val failed = assertIs<SetupOutcome.Failed>(awaitSetup(toolchain, { CoroutineScope(Dispatchers.Default).launch { toolchain.install() } }, wait))
        assertEquals(SetupOutcome.Failed(ToolchainFailure.Output, "boom"), failed)
    }

    @Test
    fun `awaitSetup is false when the install is cancelled`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { command, _, _ -> if (command.startsWith("cat ")) result(1) else awaitCancellation() }, config)
        // On this test's one thread, the install and its cancelling only run while awaitSetup waits, so awaitSetup
        // always sees the setup start and then fail; on other threads both could finish before it looks.
        val install = launch { toolchain.install() }
        val cancelling = launch { toolchain.state.first { it is ToolchainState.Installing }; install.cancel() }
        assertEquals(ToolchainFailure.Interrupted, assertIs<SetupOutcome.Failed>(awaitSetup(toolchain, {}, wait)).kind)
        cancelling.join()
        assertEquals(ToolchainState.Failed(ToolchainFailure.Interrupted), toolchain.state.value)
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
        assertIs<SetupOutcome.Failed>(awaitSetup(toolchain, {}, 50.milliseconds))
    }

    @Test
    fun `without Linux the setup never starts`() = runBlocking<Unit> {
        val shell = FakeShell { _, _, _ -> result(1) }
        var started = false
        assertEquals(SetupOutcome.NeedsLinux, prepareAppBuilding(linuxReady = false, AppToolchain(shell, config), { started = true }, wait))
        assertFalse(started)
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `with Linux and a toolchain already there, setup is ready without starting`() = runBlocking<Unit> {
        var started = false
        val toolchain = AppToolchain(FakeShell { _, _, _ -> result(0, AppToolchain.pinsHash(config)) }, config)
        assertEquals(SetupOutcome.Ready, prepareAppBuilding(linuxReady = true, toolchain, { started = true }, wait))
        assertFalse(started)
    }

    @Test
    fun `a failure's reason is capped`() = runBlocking<Unit> {
        val toolchain = AppToolchain(FakeShell { command, _, _ -> if (command.startsWith("cat ")) result(1) else result(2, "", "x".repeat(config.maxReasonChars * 3)) }, config)
        assertIs<Outcome.Failure>(toolchain.install())
        assertEquals(config.maxReasonChars, assertIs<ToolchainState.Failed>(toolchain.state.value).detail?.length)
        val thrown = assertIs<SetupOutcome.Failed>(awaitSetup(toolchain, { throw IllegalStateException("y".repeat(config.maxReasonChars * 3)) }, wait))
        assertEquals(config.maxReasonChars, thrown.detail?.length)
    }

    @Test
    fun `the card shows the last line of a reason`() {
        assertEquals("E: Unable to locate package aapt", shortReason("Reading lists...\nE: Unable to locate package aapt\n\n"))
        assertEquals("boom", shortReason("boom"))
    }

    @Test
    fun `the agent hears each kind of failure in config words, with the raw detail after`() {
        val messages = assertIs<Outcome.Success<AgentMessages>>(
            ConfigLoader(BundledConfigSource()).load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION),
        ).value
        val words = ToolchainFailure.entries.map { setupReasonForModel(messages, it, detail = null) }
        assertEquals(ToolchainFailure.entries.size, words.toSet().size, "each kind has its own words")
        assertTrue(words.all { it.isNotBlank() })
        assertEquals(messages.appSetupTimedOut, setupReasonForModel(messages, ToolchainFailure.TimedOut, null))
        assertEquals(messages.appSetupOutput + "\nE: no space", setupReasonForModel(messages, ToolchainFailure.Output, "E: no space"))
    }

    @Test
    fun `Remove is offered for a set-up toolchain or a half-finished one, but not during setup or a build`() {
        assertTrue(Toolchain(ready = true, sizeBytes = 400_000_000L, installing = false, building = false).removable)
        assertTrue(Toolchain(ready = false, sizeBytes = 120_000_000L, installing = false, building = false).removable, "a broken setup can be cleared")
        assertFalse(Toolchain(ready = false, sizeBytes = null, installing = false, building = false).removable)
        assertFalse(Toolchain(ready = true, sizeBytes = 400_000_000L, installing = true, building = false).removable)
        assertFalse(Toolchain(ready = true, sizeBytes = 400_000_000L, installing = false, building = true).removable)
    }

    @Test
    fun `remove refuses while a build holds the toolchain`() = runBlocking<Unit> {
        val hash = AppToolchain.pinsHash(config)
        val shell = FakeShell { command, _, _ -> if (command.startsWith("cat ")) result(0, hash) else result(0) }
        val toolchain = AppToolchain(shell, config)
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val build = launch(Dispatchers.Default) { toolchain.whileReady { holding.complete(Unit); release.await() } }
        holding.await()
        assertTrue(toolchain.building.value)
        assertEquals(ToolchainFailure.InUse, toolchain.remove())
        assertTrue(shell.calls.none { it.first.startsWith("rm ") })
        release.complete(Unit)
        build.join()
        assertFalse(toolchain.building.value)
        assertEquals(null, toolchain.remove())
        assertTrue(shell.calls.any { it.first.startsWith("rm ") })
    }

    @Test
    fun `a build doesn't start while the toolchain is being removed, or when it isn't ready`() = runBlocking<Unit> {
        val hash = AppToolchain.pinsHash(config)
        val removing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val shell = FakeShell { command, _, _ ->
            if (command.startsWith("rm ")) {
                removing.complete(Unit)
                finish.await()
            }
            if (command.startsWith("cat ")) result(0, hash) else result(0)
        }
        val toolchain = AppToolchain(shell, config)
        val remove = launch(Dispatchers.Default) { toolchain.remove() }
        removing.await()
        var ran = false
        assertEquals(null, toolchain.whileReady { ran = true })
        assertFalse(ran)
        finish.complete(Unit)
        remove.join()

        val missing = AppToolchain(FakeShell { _, _, _ -> result(1) }, config)
        assertEquals(null, missing.whileReady { ran = true })
        assertFalse(ran)
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

    @Test
    fun `du's kibibytes become bytes, and odd output none`() {
        assertEquals(153_600L * 1024, duBytes("153600\t/opt/whittle-apps\n"))
        assertNull(duBytes("du: cannot access '/opt/whittle-apps'"))
        assertNull(duBytes(""))
    }
}
