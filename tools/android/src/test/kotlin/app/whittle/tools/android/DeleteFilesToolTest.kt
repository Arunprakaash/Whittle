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

    private companion object {
        const val NOT_DELETED = "won't be deleted"

        /** The words as agent-messages.json has them, so the card lines below read as they will. */
        val WORDS = DeleteFilesWords(
            notDeleted = NOT_DELETED,
            gap = "  ·  ",
            noAccess = "Whittle can't see your files yet",
            notAList = "paths must be a list of files, like [\"/sdcard/Download/a.jpg\"]",
            notAPath = "every entry in paths must be a file path",
            empty = "paths is empty",
            tooMany = "at most 2 files per call, so the user can review them; split the list into several calls",
            noApprovedList = "nothing was deleted: the user hasn't approved a list of files for this call; call delete_files again so they see it",
            controlCharacter = "has a line break or control character in it",
            outsideStorage = "must be a file in shared storage, like /sdcard/Download/a.jpg, with no ..",
            missing = "doesn't exist",
            unreadable = "can't be read",
            link = "is a link, not a file",
            folder = "is a folder; only files are deleted",
            notRegular = "isn't a regular file",
            throughLink = "goes through a link",
            listedTwice = "is listed twice",
            notOnCard = "wasn't on the list the user approved",
            refusedByAndroid = "Android didn't let it be deleted",
        )
    }

    private fun file(dir: File, path: String, bytes: Int = 10): File =
        File(dir, path).also { it.parentFile.mkdirs() }.apply { writeBytes(ByteArray(bytes)) }

    private fun tool(maxPaths: Int = 10) = DeleteFilesTool(storage, DeleteFilesLimits(maxPaths), WORDS) { scanned += it }

    private fun args(vararg paths: String) = JsonObject(mapOf("paths" to JsonArray(paths.map { JsonPrimitive(it) })))

    /** The call as the runner makes it: previewed when parked, then run with what that card approved. */
    private fun run(vararg paths: String, maxPaths: Int = 10): ToolOutput = runBlocking {
        val approved = tool(maxPaths).preview(args(*paths)).approved
        tool(maxPaths).execute(args(*paths), ctx.copy(approved = approved))
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
            assertTrue(runBlocking { DeleteFilesTool(storage, DeleteFilesLimits(10), WORDS) {}.execute(args, ctx.copy(approved = emptyList())) }.isError)
        }
        assertTrue(File(root, "a.jpg").exists())
    }

    @Test
    fun refusesANameThatWouldReadAsTwoLinesOnTheCard() {
        file(root, "a.jpg")

        val results = results("a.jpg\n/sdcard/b.jpg")

        assertEquals("refused", results.status("a.jpg\n/sdcard/b.jpg"))
        assertEquals("a.jpg?/sdcard/b.jpg  ·  $NOT_DELETED: has a line break or control character in it", preview("a.jpg\n/sdcard/b.jpg").getValue(DeleteFilesTool.LISTING))
    }

    @Test
    fun asksForStorageWhenNotGranted() {
        val none = object : SharedStorage {
            override fun root(): File? = null
        }
        val out = runBlocking { DeleteFilesTool(none, DeleteFilesLimits(10), WORDS) {}.execute(JsonObject(mapOf("paths" to JsonArray(listOf(JsonPrimitive("a.jpg"))))), ctx) }
        assertEquals(AndroidCapabilities.STORAGE, out.missingCapability)
    }

    private fun preview(vararg paths: String, maxPaths: Int = 10): Map<String, String> = runBlocking { tool(maxPaths).preview(args(*paths)).card }

    @Test
    fun aFileThatAppearsAfterTheCardIsNotDeleted() {
        val listed = file(root, "a.jpg")
        val approved = runBlocking { tool().preview(args("a.jpg", "late.jpg")) }.approved
        val late = file(root, "late.jpg")

        val out = runBlocking { tool().execute(args("a.jpg", "late.jpg"), ctx.copy(approved = approved)) }

        val files = Json.parseToJsonElement(out.content).jsonObject.getValue("files").jsonArray.map { it.jsonObject.getValue("status").jsonPrimitive.content }
        assertEquals(listOf("deleted", "refused"), files)
        assertFalse(listed.exists())
        assertTrue(late.exists())
    }

    @Test
    fun withoutAnApprovedCardNothingIsDeleted() {
        val target = file(root, "a.jpg")

        val out = runBlocking { tool().execute(args("a.jpg"), ctx) }

        assertTrue(out.isError)
        assertTrue("nothing was deleted" in out.content)
        assertTrue(target.exists())
        assertTrue(scanned.isEmpty())
    }

    @Test
    fun aCallWhereNothingCanGoGetsTheNothingCard() {
        val preview = runBlocking { tool().preview(args("/sdcard/gone.jpg", "/etc/hosts")) }
        assertEquals(DeleteFilesTool.NOTHING, preview.variant)
        assertTrue(preview.approved.isEmpty())
        assertEquals("0", preview.card.getValue(DeleteFilesTool.COUNT))

        file(root, "a.jpg")
        assertEquals(null, runBlocking { tool().preview(args("a.jpg")) }.variant)
    }

    @Test
    fun namesThatCouldReorderOrBreakALineShowAsQuestionMarks() {
        file(root, "photo\u202Egpj.exe", bytes = 10)

        assertEquals("/sdcard/photo?gpj.exe  ·  10 B", preview("photo\u202Egpj.exe").getValue(DeleteFilesTool.LISTING))
        assertEquals("a?b  ·  $NOT_DELETED: doesn't exist", preview("a\u2028b").getValue(DeleteFilesTool.LISTING))
        assertEquals("x?y", shown("x\u2029y"))
        assertEquals("x?y", shown("x\uDB40\uDC01y"))
    }

    @Test
    fun previewListsRealPathsWithSizesAndTheTotal() {
        file(root, "Download/a.jpg", bytes = 1_500)
        file(root, "Download/b.pdf", bytes = 2_500)

        val card = preview("/sdcard/Download/a.jpg", "Download/b.pdf")

        assertEquals("/sdcard/Download/a.jpg  ·  1.5 KB\n/sdcard/Download/b.pdf  ·  2.5 KB", card.getValue(DeleteFilesTool.LISTING))
        assertEquals("2", card.getValue(DeleteFilesTool.COUNT))
        assertEquals("4.0 KB", card.getValue(DeleteFilesTool.TOTAL))
        assertTrue(File(root, "Download/a.jpg").exists() && File(root, "Download/b.pdf").exists())
        assertTrue(scanned.isEmpty())
    }

    @Test
    fun previewShowsARefusedPathAsNotDeleted() {
        file(root, "a.jpg", bytes = 10)
        File(root, "Music").mkdirs()

        val card = preview("a.jpg", "/sdcard/Music", "/sdcard/gone.mp4")

        val lines = card.getValue(DeleteFilesTool.LISTING).lines()
        assertEquals("/sdcard/a.jpg  ·  10 B", lines[0])
        assertEquals("/sdcard/Music  ·  $NOT_DELETED: is a folder; only files are deleted", lines[1])
        assertEquals("/sdcard/gone.mp4  ·  $NOT_DELETED: doesn't exist", lines[2])
        assertEquals("1", card.getValue(DeleteFilesTool.COUNT))
        assertEquals("10 B", card.getValue(DeleteFilesTool.TOTAL))
    }

    @Test
    fun previewOfACallThatWillBeRefusedSaysNothingGoes() {
        val card = preview("a", "b", "c", maxPaths = 2)

        assertTrue(card.getValue(DeleteFilesTool.LISTING).startsWith("$NOT_DELETED: at most 2 files per call"))
        assertEquals("0", card.getValue(DeleteFilesTool.COUNT))
    }

    @Test
    fun sizesReadLikeAFileManager() {
        assertEquals("999 B", readableSize(999))
        assertEquals("1.0 KB", readableSize(1_000))
        assertEquals("12 MB", readableSize(12_300_000))
        assertEquals("3.2 GB", readableSize(3_200_000_000))
    }

    @Test
    fun deletingIsTheRiskiestTierAndNeedsStorage() {
        assertEquals(RiskTier.DELETE_OR_PAY, DeleteFilesTool.DEFINITION.riskTier)
        assertEquals(setOf(AndroidCapabilities.STORAGE), DeleteFilesTool.DEFINITION.requiredCapabilities)
    }
}
