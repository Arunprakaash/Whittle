package app.whittle.runtimes

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
import kotlin.test.assertTrue

/**
 * The shipped tools, in the app's own process and SELinux domain: what Android allows there is what
 * matters, and only a device can tell. Runs offline.
 */
@RunWith(AndroidJUnit4::class)
class ToolRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val defaults = assertIs<Outcome.Success<AppDefaults>>(
        ConfigLoader(BundledConfigSource()).load(AppDefaults.FILE, AppDefaults.serializer(), AppDefaults.SCHEMA_VERSION),
    ).value
    private val workspace = File(context.filesDir, "tool-runtime-test").apply { mkdirs() }
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

    @After
    fun cleanUp() {
        workspace.deleteRecursively()
    }

    @Test
    fun nodeAndNpmRun() = runBlocking<Unit> {
        val result = run("node -v && npm -v")
        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.startsWith("v"), result.stdout)
    }

    @Test
    fun nodeServesHttpOnLoopback() = runBlocking<Unit> {
        val script = """require("http").createServer((q, r) => r.end("ok")).listen(0, "127.0.0.1", function () {""" +
            """ fetch("http://127.0.0.1:" + this.address().port).then(r => r.text()).then(t => { console.log(t); process.exit(0) }) })"""
        File(workspace, "server.js").writeText(script)
        val result = run("node server.js", network = true)
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("ok", result.stdout.trim())
    }

    @Test
    fun npmRunsPackageScripts() = runBlocking<Unit> {
        File(workspace, "hello.js").writeText("console.log('hi from ' + process.release.name)\n")
        File(workspace, "package.json").writeText("""{"name":"hello","version":"1.0.0","scripts":{"hello":"node hello.js"}}""")
        val result = run("npm run --silent hello")
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("hi from node", result.stdout.trim())
    }

    @Test
    fun pythonAndPipRun() = runBlocking<Unit> {
        val result = run("python3 -c 'import json, sqlite3, ssl; print(json.dumps(sqlite3.sqlite_version_info[0] >= 3))' && pip --version")
        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.startsWith("true\npip "), result.stdout)
    }

    @Test
    fun gitCommits() = runBlocking<Unit> {
        File(workspace, "notes.txt").writeText("hello\n")
        val result = run(
            "git init -q && git -c user.name=Whittle -c user.email=whittle@localhost add notes.txt && " +
                "git -c user.name=Whittle -c user.email=whittle@localhost commit -qm first && git log --format=%s",
        )
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("first", result.stdout.trim())
    }

    private suspend fun run(command: String, network: Boolean = false): CommandResult =
        runner.run(command, workspace, TIMEOUT_SECONDS, network)

    private companion object {
        const val TIMEOUT_SECONDS = 60
    }
}
