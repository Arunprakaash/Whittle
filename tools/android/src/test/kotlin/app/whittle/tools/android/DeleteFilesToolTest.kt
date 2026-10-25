package app.whittle.tools.android

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeleteFilesToolTest {
    private val base: File = Files.createTempDirectory("delete-tool").toFile().canonicalFile.also { it.deleteOnExit() }
    private val root = File(base, "sdcard").also { it.mkdirs() }
    private val outside = File(base, "outside").also { it.mkdirs() }
    private val storage = object : SharedStorage {
        override fun root() = root
    }
    private val scanned = mutableListOf<String>()
    private val ctx = ToolExecutionContext(RunId("r"), ToolCallId("c"))

    private fun file(dir: File, path: String, bytes: Int = 10): File =
        File(dir, path).also { it.parentFile.mkdirs() }.apply { writeBytes(ByteArray(bytes)) }

    private fun run(vararg paths: String, maxPaths: Int = 10): ToolOutput {
        val args = JsonObject(mapOf("paths" to JsonArray(paths.map { JsonPrimitive(it) })))
        return runBlocking { DeleteFilesTool(storage, maxPaths) { scanned += it }.execute(args, ctx) }
    }

    private fun results(vararg paths: String): Map<String, JsonObject> {
        val out = run(*paths)
        assertFalse(out.isError, out.content)
        return Json.parseToJsonElement(out.content).jsonObject.getValue("files").jsonArray
            .associate { it.jsonObject.getValue("path").jsonPrimitive.content to it.jsonObject }
    }

    private fun Map<String, JsonObject>.status(path: String) = getValue(path).getValue("status").jsonPrimitive.content

    @Test
    fun refusesPathsOutsideSharedStorage() {
        val target = file(outside, "keep.txt")

        val results = results(target.path, "/sdcard/../outside/keep.txt", "/etc/hosts")

        assertEquals(listOf("refused", "refused", "refused"), results.keys.map { results.status(it) })
        assertTrue(target.exists())
        assertTrue(scanned.isEmpty())
    }

    @Test
    fun refusesDotDotEvenWhenItStaysInside() {
        val target = file(root, "Download/a.jpg")

        val results = results("/sdcard/Download/../Download/a.jpg")

        assertEquals("refused", results.status("/sdcard/Download/../Download/a.jpg"))
        assertTrue(target.exists())
    }

    @Test
    fun refusesFolders() {
        val folder = File(root, "Download").also { it.mkdirs() }
        file(folder, "a.jpg")

        val results = results("/sdcard/Download", "/sdcard", "Download/")

        assertEquals(setOf("refused"), results.keys.map { results.status(it) }.toSet())
        assertTrue(File(folder, "a.jpg").exists())
    }

    @Test
    fun refusesALinkToAFileOutside() {
        val target = file(outside, "secret.txt")
        Files.createSymbolicLink(File(root, "link.txt").toPath(), target.toPath())

        val results = results("/sdcard/link.txt")

        assertEquals("refused", results.status("/sdcard/link.txt"))
        assertTrue(target.exists())
        assertTrue(Files.isSymbolicLink(File(root, "link.txt").toPath()))
    }

    @Test
    fun refusesAPathThroughALinkedFolder() {
        val target = file(root, "Real/a.jpg")
        Files.createSymbolicLink(File(root, "Alias").toPath(), File(root, "Real").toPath())

        // The card would show /sdcard/Alias/a.jpg while /sdcard/Real/a.jpg went, so it's refused.
        val results = results("/sdcard/Alias/a.jpg")

        assertEquals("refused", results.status("/sdcard/Alias/a.jpg"))
        assertTrue(target.exists())
    }

    @Test
    fun deletesListedFilesOnly() {
        val first = file(root, "Download/a.jpg", bytes = 100)
        val second = file(root, "Download/b.pdf", bytes = 50)
        val kept = file(root, "Download/c.jpg")

        val out = run("/sdcard/Download/a.jpg", "Download/b.pdf", "/sdcard/Download/missing.jpg")

        assertFalse(out.isError, out.content)
        val body = Json.parseToJsonElement(out.content).jsonObject
        val results = body.getValue("files").jsonArray.associate { it.jsonObject.getValue("path").jsonPrimitive.content to it.jsonObject }
        assertEquals("deleted", results.status("/sdcard/Download/a.jpg"))
        assertEquals("deleted", results.status("Download/b.pdf"))
        assertEquals("refused", results.status("/sdcard/Download/missing.jpg"))
        assertEquals(2, body.getValue("deleted").jsonPrimitive.int)
        assertEquals(150, body.getValue("freedBytes").jsonPrimitive.long)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertTrue(kept.exists())
        assertEquals(listOf(first.path, second.path), scanned)
    }

    @Test
    fun aFileListedTwiceIsDeletedOnce() {
        file(root, "a.jpg")

        val results = run("/sdcard/a.jpg", "a.jpg")

        val files = Json.parseToJsonElement(results.content).jsonObject.getValue("files").jsonArray.map { it.jsonObject.getValue("status").jsonPrimitive.content }
        assertEquals(listOf("deleted", "refused"), files)
    }

    @Test
    fun refusesMorePathsThanTheLimitAndDeletesNothing() {
        val files = (1..3).map { file(root, "f$it.bin") }

        val out = run("f1.bin", "f2.bin", "f3.bin", maxPaths = 2)

        assertTrue(out.isError)
        assertTrue(files.all { it.exists() })
    }

    @Test
    fun refusesACallWithoutAListOfPaths() {
        file(root, "a.jpg")
        val notStrings = JsonObject(mapOf("paths" to JsonArray(listOf(JsonPrimitive("a.jpg"), JsonPrimitive(3)))))
        val notAList = JsonObject(mapOf("paths" to JsonPrimitive("a.jpg")))

        listOf(notStrings, notAList, JsonObject(emptyMap())).forEach { args ->
            assertTrue(runBlocking { DeleteFilesTool(storage, 10) {}.execute(args, ctx) }.isError)
        }
        assertTrue(File(root, "a.jpg").exists())
    }

    @Test
    fun refusesANameThatWouldReadAsTwoLinesOnTheCard() {
        file(root, "a.jpg")

        val results = results("a.jpg\n/sdcard/b.jpg")

        assertEquals("refused", results.status("a.jpg\n/sdcard/b.jpg"))
    }

    @Test
    fun asksForStorageWhenNotGranted() {
        val none = object : SharedStorage {
            override fun root(): File? = null
        }
        val out = runBlocking { DeleteFilesTool(none, 10) {}.execute(JsonObject(mapOf("paths" to JsonArray(listOf(JsonPrimitive("a.jpg"))))), ctx) }
        assertEquals(AndroidCapabilities.STORAGE, out.missingCapability)
    }

    @Test
    fun deletingIsTheRiskiestTierAndNeedsStorage() {
        assertEquals(RiskTier.DELETE_OR_PAY, DeleteFilesTool.DEFINITION.riskTier)
        assertEquals(setOf(AndroidCapabilities.STORAGE), DeleteFilesTool.DEFINITION.requiredCapabilities)
    }
}
