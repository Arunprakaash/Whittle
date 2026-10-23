package app.whittle.apps

import app.whittle.agent.AgentMessages
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.config.ConfigName
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.ChatProjects
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreateAppToolTest {
    private val root = Files.createTempDirectory("create-app").toFile()
    private val loader = ConfigLoader(BundledConfigSource())
    private val config = assertIs<Outcome.Success<NativeAppsConfig>>(
        loader.load(NativeAppsConfig.FILE, NativeAppsConfig.serializer(), NativeAppsConfig.SCHEMA_VERSION),
    ).value
    private val messages = assertIs<Outcome.Success<AgentMessages>>(
        loader.load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION),
    ).value
    private val chat = WorkspaceId("chat-1")
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"), chat)
    private val chosen = mutableListOf<String?>()
    private val projects = object : ChatProjects {
        override fun projectOf(chat: WorkspaceId): String? = chosen.lastOrNull()
        override fun choose(chat: WorkspaceId, project: String?) {
            chosen += project
        }
    }
    private val installed = mutableSetOf<String>()

    private fun tool(whittle: String = "app.whittle.debug") = CreateAppTool(
        Workspaces(root), projects, config, whittle, messages, { it in installed },
        { path -> (BundledConfigSource().read(ConfigName("native-app-template/$path")) as Outcome.Success).value },
    )

    private fun create(name: String) = runBlocking {
        tool().execute(buildJsonObject { put("name", name) }, context)
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `slugs make valid packages`() {
        assertEquals("my_cafe", slugFor("My Café ☕"))
        assertEquals("app_2048", slugFor("2048"))
        assertEquals("app", slugFor("日記"))
        assertEquals("app_class", slugFor("class"))
        assertEquals("app_new", slugFor("New"))
        assertEquals("app_object", slugFor("Object"))
        assertEquals("app_when", slugFor("when"))
        assertEquals(40, slugFor("a".repeat(100)).length)
        assertTrue(ChatProjects.isValidName(slugFor("!!weird -- name!!")))
        val refused = create("   ")
        assertTrue(refused.isError)
        assertEquals(messages.appNameEmpty, refused.content)
    }

    @Test
    fun `a package already on the phone or in the workspace gets a number`() {
        installed += "app.whittle.made.counter"
        val second = create("Counter")
        assertTrue("app.whittle.made.counter2" in second.content, second.content)
        assertTrue(File(root, "counter2/whittle-app.json").isFile)

        File(root, "notes").mkdirs()
        assertTrue("app.whittle.made.notes2" in create("Notes").content)
        assertTrue("app.whittle.made.notes3" in create("Notes").content)
    }

    @Test
    fun `creates the template, whittle-app json at version 0, and focuses the chat on it`() {
        val output = create("Tom & \"Jerry's\" <Timer>")
        assertFalse(output.isError, output.content)
        val folder = File(root, "tom_jerry_s_timer")
        val project = assertNotNull(AppProjects.read(folder))
        assertEquals(AppProject("Tom & \"Jerry's\" <Timer>", "app.whittle.made.tom_jerry_s_timer", 0), project)
        assertEquals(messages.appCreated(project.name, "tom_jerry_s_timer", project.packageName), output.content)
        assertEquals(listOf<String?>("tom_jerry_s_timer"), chosen)
        config.template.forEach { assertTrue(File(folder, it).isFile, it) }

        val all = config.template.joinToString("\n") { File(folder, it).readText() }
        assertFalse("{{" in all, "every placeholder is filled")
        assertTrue("package ${project.packageName}" in File(folder, "src/MainActivity.kt").readText())
        assertTrue("content://app.whittle.debug.apps.crashes/crashes" in File(folder, "src/CrashReporter.kt").readText())
        assertTrue("<package android:name=\"app.whittle.debug\"/>" in File(folder, "AndroidManifest.xml").readText())
        assertTrue(
            "Tom &amp; \\\"Jerry\\'s\\\" &lt;Timer&gt;" in File(folder, "res/values/strings.xml").readText().replace("\\&quot;", "\\\""),
            File(folder, "res/values/strings.xml").readText(),
        )
        val colour = Regex("fillColor=\"(#[0-9A-F]{6})\"").find(File(folder, "res/drawable/ic_launcher.xml").readText())!!.groupValues[1]
        assertTrue(colour in config.palette)
    }

    @Test
    fun `the colour depends only on the name`() {
        create("Stable")
        val first = File(root, "stable/res/drawable/ic_launcher.xml").readText()
        File(root, "stable").deleteRecursively()
        create("Stable")
        assertEquals(first, File(root, "stable/res/drawable/ic_launcher.xml").readText())
    }

    @Test
    fun `a run with no workspace is refused`() = runBlocking<Unit> {
        val output = tool().execute(buildJsonObject { put("name", "X") }, ToolExecutionContext(RunId("run"), ToolCallId("call")))
        assertTrue(output.isError)
        assertEquals(messages.appNoWorkspace, output.content)
        assertNull(chosen.lastOrNull())
    }
}
