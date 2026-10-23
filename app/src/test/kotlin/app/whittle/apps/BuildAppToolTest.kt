package app.whittle.apps

import app.whittle.agent.AgentMessages
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.CommandResult
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildAppToolTest {
    private val root = Files.createTempDirectory("build-app").toFile()
    private val loader = ConfigLoader(BundledConfigSource())
    private val config = assertIs<Outcome.Success<NativeAppsConfig>>(
        loader.load(NativeAppsConfig.FILE, NativeAppsConfig.serializer(), NativeAppsConfig.SCHEMA_VERSION),
    ).value
    private val messages = assertIs<Outcome.Success<AgentMessages>>(
        loader.load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION),
    ).value
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"), WorkspaceId("chat-1"))
    private val key = softwareKey()
    private val package0 = "app.whittle.made.notes"

    private var ready = true
    private val commands = mutableListOf<String>()
    private val networks = mutableListOf<Boolean>()
    private var outcome: (File) -> CommandResult = { folder -> succeed(folder) }
    private val saved = mutableListOf<String>()

    private val shell = LinuxShell { command, network, _ ->
        commands += command
        networks += network
        val folder = File(root, Regex("cd \"/workspace/([^\"]+)\"").find(command)!!.groupValues[1])
        Outcome.Success(outcome(folder))
    }

    private fun succeed(folder: File): CommandResult {
        val project = AppProjects.read(folder)!!
        val apk = File(folder, "build/${folder.name}-${project.versionCode + 1}-unsigned.apk")
        apk.parentFile.mkdirs()
        ZipOutputStream(apk.outputStream()).use { it.putNextEntry(ZipEntry("AndroidManifest.xml")); it.closeEntry() }
        return CommandResult(0, "", "", false, false, 5)
    }

    private var facts: (File) -> Outcome<ApkFacts> = { Outcome.Success(ApkFacts(package0, 1L, listOf("android.permission.CAMERA"), emptyList(), false)) }
    private var signing: Outcome<SigningKey> = Outcome.Success(key)

    private fun tool() = BuildAppTool(
        Workspaces(root), config, messages, { ready }, shell, AppSigner { signing }, { facts(it) },
        { _, _, _, content -> saved += content; Outcome.Success(".whittle/outputs/run-call.txt") },
    )

    private fun project(name: String = "notes", versionCode: Int = 0, manifestPackage: String = package0, extra: String = "", stored: String = package0): File {
        val folder = File(root, name).apply { mkdirs() }
        AppProjects.write(folder, AppProject("Notes", stored, versionCode))
        File(folder, "AndroidManifest.xml").writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$manifestPackage" $extra>
            <uses-permission android:name="android.permission.CAMERA"/></manifest>""",
        )
        return folder
    }

    private fun build(name: String) = runBlocking { tool().execute(buildJsonObject { put("project", name) }, context) }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `without a toolchain it asks for app_building`() {
        project()
        ready = false
        val output = build("notes")
        assertEquals(APP_BUILDING, output.missingCapability)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `each build raises the version code and writes the signed apk`() {
        val folder = project()
        val first = build("notes")
        assertFalse(first.isError, first.content)
        val json = Json.parseToJsonElement(first.content).jsonObject
        assertEquals(1, json["versionCode"]!!.jsonPrimitive.int)
        assertEquals("notes/build/notes-1.apk", json["apk"]!!.jsonPrimitive.content)
        assertEquals(package0, json["package"]!!.jsonPrimitive.content)
        assertEquals("Notes", json["app"]!!.jsonPrimitive.content)
        assertTrue(File(folder, "build/notes-1.apk").isFile)
        assertEquals(File(folder, "build/notes-1.apk").length(), json["sizeBytes"]!!.jsonPrimitive.content.toLong())
        assertFalse(File(folder, "build/notes-1-unsigned.apk").exists())
        assertEquals(1, AppProjects.read(folder)!!.versionCode)
        assertEquals(listOf("android.permission.CAMERA"), json["permissions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonArray(emptyList()), json["powerful"])

        build("notes")
        assertTrue(File(folder, "build/notes-2.apk").isFile)
        assertEquals(2, AppProjects.read(folder)!!.versionCode)
    }

    @Test
    fun `the build runs with network off and the script's placeholders filled`() {
        project()
        build("notes")
        assertEquals(listOf(false), networks)
        assertTrue(commands.single().startsWith("set -euo pipefail"))
        assertTrue("notes-1-unsigned.apk" in commands.single())
        assertFalse("{{" in commands.single())
    }

    @Test
    fun `powerful declarations are written with name kind and sentence`() {
        project()
        val declaration = config.powerful.single { it.name == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" }
        facts = { Outcome.Success(ApkFacts(package0, 1L, emptyList(), listOf(declaration.name), false)) }
        val json = Json.parseToJsonElement(build("notes").content).jsonObject
        val entry = json["powerful"]!!.jsonArray.single().jsonObject
        assertEquals(declaration.name, entry["name"]!!.jsonPrimitive.content)
        assertEquals("SERVICE_PERMISSION", entry["kind"]!!.jsonPrimitive.content)
        assertEquals(declaration.sentence, entry["sentence"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failed build keeps the version code, returns parsed errors and saves the full output`() {
        val folder = project(versionCode = 3)
        outcome = { CommandResult(1, "out", "noise\nsrc/A.kt:1:2: error: boom\nmore", false, false, 5) }
        val output = build("notes")
        assertTrue(output.isError)
        assertTrue("src/A.kt:1:2: error: boom" in output.content)
        assertFalse("noise" in output.content)
        assertTrue("run-call.txt" in output.content)
        assertTrue("noise" in saved.single())
        assertEquals(3, AppProjects.read(folder)!!.versionCode)
    }

    @Test
    fun `refuses an APK with a sharedUserId and leaves no APK or version bump`() {
        val folder = project()
        facts = { Outcome.Success(ApkFacts(package0, 1L, emptyList(), emptyList(), true)) }
        val output = build("notes")
        assertTrue(output.isError)
        assertEquals(messages.appSharedUserId, output.content)
        assertNoApk(folder)
    }

    @Test
    fun `refuses a built APK whose package differs from the project's`() {
        val folder = project()
        facts = { Outcome.Success(ApkFacts("com.evil.app", 1L, emptyList(), emptyList(), false)) }
        val output = build("notes")
        assertTrue(output.isError)
        assertEquals(messages.appPackageMismatch(package0), output.content)
        assertNoApk(folder)
    }

    @Test
    fun `an APK that can't be inspected is a failure`() {
        val folder = project()
        facts = { Outcome.Failure(HarnessError.Storage("unreadable")) }
        assertTrue(build("notes").isError)
        assertNoApk(folder)
    }

    @Test
    fun `refuses a stored package that is not the prefix plus the folder name`() {
        project(stored = "app.whittle.made.other")
        assertEquals(messages.appNoProject("notes"), build("notes").content)
        project(name = "plain", stored = "com.evil.app")
        assertEquals(messages.appNoProject("plain"), build("plain").content)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `exit 0 with no APK says so`() {
        val folder = project()
        outcome = { CommandResult(0, "", "", false, false, 5) }
        assertEquals(messages.appBuildNoApk, build("notes").content)
        assertNoApk(folder)
    }

    @Test
    fun `a timeout says so`() {
        val folder = project()
        outcome = { CommandResult(null, "", "", true, false, 5) }
        val output = build("notes")
        assertTrue(output.isError)
        assertEquals(messages.appBuildTimedOut(config.buildTimeoutSeconds), output.content)
        assertNoApk(folder)
    }

    @Test
    fun `a signer failure leaves no unsigned APK and no version bump`() {
        val folder = project()
        signing = Outcome.Failure(HarnessError.Storage("no key"))
        assertTrue(build("notes").isError)
        assertNoApk(folder)
    }

    @Test
    fun `errors are parsed from stdout too`() {
        project()
        outcome = { CommandResult(1, "src/A.kt:1:2: error: from stdout", "noise", false, false, 5) }
        assertTrue("from stdout" in build("notes").content)
    }

    private fun assertNoApk(folder: File) {
        assertEquals(0, AppProjects.read(folder)!!.versionCode)
        assertEquals(emptyList<String>(), File(folder, "build").listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `refuses project names that are not plain folders`() {
        File(root, "x").mkdirs()
        listOf("../x", "a b", "", "a/b", ".hidden", "x; rm -rf /").forEach { name ->
            val output = build(name)
            assertTrue(output.isError, name)
            assertEquals(messages.appNoProject(name), output.content, name)
        }
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `refuses a folder that is not an app project`() {
        File(root, "plain").mkdirs()
        assertEquals(messages.appNoProject("plain"), build("plain").content)
        assertEquals(messages.appNoProject("missing"), build("missing").content)
    }

    @Test
    fun `a run with no workspace is refused`() = runBlocking<Unit> {
        project()
        val output = tool().execute(buildJsonObject { put("project", "notes") }, ToolExecutionContext(RunId("run"), ToolCallId("call")))
        assertEquals(messages.appNoWorkspace, output.content)
    }

    /** A software RSA key and self-signed certificate, made by the JDK's keytool. */
    private fun softwareKey(): SigningKey {
        val store = File(root, "fake.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "fake", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=fake", "-keystore", store.path, "-storetype", "PKCS12", "-storepass", "changeit",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        val keyStore = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
        val certificate = assertNotNull(keyStore.getCertificate("fake")) as X509Certificate
        store.delete()
        return SigningKey(keyStore.getKey("fake", "changeit".toCharArray()) as PrivateKey, certificate)
    }
}
