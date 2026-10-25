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

        assertEquals(listOf("big", "medium", "loose.bin"), result.map { it.name })
        assertEquals(listOf(600L, 200L, 50L), result.map { it.bytes })
        assertTrue(result.none { it.partial })
    }

    @Test
    fun deadlineMarksPartial() {
        file("a/one.bin", 10)
        file("a/two.bin", 10)
        file("b/one.bin", 10)
        var clock = 0L

        // Every look at the clock costs a second, so the deadline passes part way through.
        val result = topFolders(root, limit = 10, deadlineMillis = 2_000, now = { clock += 1_000; clock })

        assertTrue(result.any { it.partial })
        assertFalse(result.isEmpty())
    }
}
