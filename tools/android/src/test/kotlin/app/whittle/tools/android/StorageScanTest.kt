package app.whittle.tools.android

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageScanTest {
    private val root: File = Files.createTempDirectory("storage-scan").toFile().also { it.deleteOnExit() }

    private fun file(path: String, bytes: Int) {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    @Test
    fun largestFirst() {
        file("small/a.bin", 10)
        file("big/deep/b.bin", 500)
        file("big/c.bin", 100)
        file("medium/d.bin", 200)
        file("loose.bin", 50)

        val result = topFolders(root, limit = 3, deadlineMillis = 1_000, now = { 0L })
        assertFalse(result.partial)
        val folders = result.folders

        assertEquals(listOf("big", "medium", "loose.bin"), folders.map { it.name })
        assertEquals(listOf(600L, 200L, 50L), folders.map { it.bytes })
        assertTrue(folders.none { it.partial })
    }

    @Test
    fun foldersCutOffByTheDeadlineAreReportedEvenWhenTheLimitDropsThem() {
        file("a/one.bin", 10)
        file("b/one.bin", 10)
        file("c/one.bin", 10)
        file("d/one.bin", 10)
        var clock = 0L

        // Every look at the clock costs a second: the first folders are measured, the later ones are never reached.
        val result = topFolders(root, limit = 1, deadlineMillis = 2_500, now = { clock += 1_000; clock })

        assertEquals(1, result.folders.size)
        // The one listed was measured, but others were cut short and dropped by the limit, so the list can't be trusted.
        assertFalse(result.folders.single().partial)
        assertTrue(result.partial)
    }

    @Test
    fun bytesOlderThanCountsOnlyOldFilesAndStopsAtTheDeadline() {
        file("old/a.bin", 10)
        file("new.bin", 20)
        File(root, "old/a.bin").setLastModified(1_000)
        File(root, "new.bin").setLastModified(9_000)

        assertEquals(Measured(10, false), bytesOlderThan(root, 5_000, deadlineMillis = 1_000, now = { 0L }))

        var clock = 0L
        val cut = bytesOlderThan(root, 5_000, deadlineMillis = 1, now = { clock++ })
        assertTrue(cut.partial)
    }
}
