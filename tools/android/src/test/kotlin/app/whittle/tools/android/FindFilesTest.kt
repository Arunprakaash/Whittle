package app.whittle.tools.android

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindFilesTest {
    private val root: File = Files.createTempDirectory("find-files").toFile().canonicalFile.also { it.deleteOnExit() }
    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val types = FileTypes(
        mapOf(
            "image" to listOf("jpg", "png"),
            "video" to listOf("mp4"),
            "audio" to listOf("mp3"),
            "document" to listOf("pdf"),
            "apk" to listOf("apk"),
            "archive" to listOf("zip"),
        ),
    )

    private fun file(path: String, size: Int, ageDays: Long = 0): File {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(size))
        f.setLastModified(now - TimeUnit.DAYS.toMillis(ageDays))
        return f
    }

    private fun find(filter: FileFilter = FileFilter(), deadline: Long = Long.MAX_VALUE, clock: () -> Long = { 0L }) =
        findFiles(root, filter, types, deadline, clock)

    @Test
    fun filtersCombine() {
        file("old-big.mp4", 5_000, ageDays = 200)
        file("old-small.mp4", 10, ageDays = 200)
        file("new-big.mp4", 6_000, ageDays = 1)
        file("old-big.pdf", 7_000, ageDays = 200)
        file("deep/old-big.zip", 8_000, ageDays = 300)

        val filter = FileFilter(minBytes = 1_000, olderThanMillis = now - TimeUnit.DAYS.toMillis(90), types = setOf("video", "archive"))
        val found = find(filter).files

        assertEquals(listOf("deep/old-big.zip", "old-big.mp4"), found.map { it.file.relativeTo(root).path })
        assertEquals(listOf("archive", "video"), found.map { it.type })
    }

    @Test
    fun largestFirstAndOtherType() {
        file("a.jpg", 10)
        file("b.xyz", 30)
        file("noext", 20)

        val found = find().files

        assertEquals(listOf("b.xyz", "noext", "a.jpg"), found.map { it.file.name })
        assertEquals(listOf("other", "other", "image"), found.map { it.type })
        assertEquals("image", types.typeOf("PHOTO.JPG"))
    }

    @Test
    fun symlinksAreNotFollowed() {
        file("real/a.bin", 10)
        val outside = Files.createTempDirectory("outside").toFile().also { File(it, "secret.bin").writeBytes(ByteArray(99)) }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())

        assertEquals(listOf("a.bin"), find().files.map { it.file.name })
    }

    @Test
    fun walkStopsAtTheDeadline() {
        file("a.bin", 10)
        file("b.bin", 10)

        val result = find(deadline = 100, clock = { 100L })

        assertTrue(result.partial)
        assertTrue(result.files.isEmpty())
        assertFalse(find().partial)
    }

    @Test
    fun folderMustStayInsideStorage() {
        file("Download/a.bin", 1)

        assertEquals(File(root, "Download"), resolveFolder(root, "/sdcard/Download"))
        assertEquals(File(root, "Download"), resolveFolder(root, "Download"))
        assertEquals(root, resolveFolder(root, null))
        assertEquals(root, resolveFolder(root, "/sdcard"))
        assertNull(resolveFolder(root, "../etc"))
        assertNull(resolveFolder(root, "Download/../../etc"))
        assertNull(resolveFolder(root, "/etc"))
        assertNull(resolveFolder(root, "Missing"))
        val outside = Files.createTempDirectory("outside").toFile()
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        assertNull(resolveFolder(root, "escape"))
    }

    @Test
    fun pathsAreShownFromSdcard() {
        assertEquals("/sdcard/Download/a.bin", displayPath(root, File(root, "Download/a.bin")))
        assertEquals("/sdcard", displayPath(root, root))
    }
}
