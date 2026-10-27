package app.whittle.tools.android

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicatesTest {
    private val root: File = Files.createTempDirectory("duplicates").toFile().also { it.deleteOnExit() }

    private fun file(name: String, content: ByteArray): File = File(root, name).also { it.writeBytes(content) }

    private fun bytes(size: Int, seed: Int = 1) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun names(groups: List<List<File>>) = groups.map { g -> g.map { it.name }.sorted() }.sortedBy { it.first() }

    @Test
    fun sameContentDifferentNamesGrouped() {
        val a = file("a.bin", bytes(300_000))
        val b = file("copy of a.bin", bytes(300_000))
        val other = file("c.bin", bytes(300_000, seed = 2))

        assertEquals(listOf(listOf("a.bin", "copy of a.bin")), names(duplicateGroups(listOf(a, b, other))))
    }

    @Test
    fun sameSizeDifferentContentNotGrouped() {
        val a = file("a.bin", bytes(1_000))
        val b = file("b.bin", bytes(1_000, seed = 9))

        assertTrue(duplicateGroups(listOf(a, b)).isEmpty())
    }

    @Test
    fun differenceInTheMiddleIsStillTold() {
        // Same size, same first and last 64 KiB: only the full hash can tell these apart.
        val one = bytes(400_000)
        val two = one.copyOf().also { it[200_000] = (it[200_000] + 1).toByte() }

        assertTrue(duplicateGroups(listOf(file("a.bin", one), file("b.bin", two))).isEmpty())
    }

    @Test
    fun emptyFilesAreSkipped() {
        assertTrue(duplicateGroups(listOf(file("a", ByteArray(0)), file("b", ByteArray(0)))).isEmpty())
    }

    @Test
    fun unreadableFileIsLeftOut() {
        val a = file("a.bin", bytes(500))
        val b = file("b.bin", bytes(500))
        val gone = File(root, "gone.bin")

        assertEquals(listOf(listOf("a.bin", "b.bin")), names(duplicateGroups(listOf(a, b, gone))))
    }

    @Test
    fun hashingStopsAtTheDeadline() {
        val files = listOf(file("a.bin", bytes(3_000_000)), file("b.bin", bytes(3_000_000)))
        var calls = 0
        val whole = findDuplicates(files, deadlineMillis = 1_000, now = { calls++; 0L }, maxHashBytes = Long.MAX_VALUE)
        assertFalse(whole.partial)
        assertEquals(1, whole.groups.size)
        val checks = calls

        // Time runs out at the very last check: the answer must be cut short, not finished anyway.
        var seen = 0
        val cut = findDuplicates(files, deadlineMillis = 1_000, now = { if (++seen < checks) 0L else 1_000L }, maxHashBytes = Long.MAX_VALUE)
        assertTrue(cut.partial)
        assertTrue(cut.groups.isEmpty())

        val never = findDuplicates(files, deadlineMillis = 0, now = { 0L }, maxHashBytes = Long.MAX_VALUE)
        assertTrue(never.partial)
    }

    @Test
    fun byteCapMarksPartial() {
        val files = listOf(file("a.bin", bytes(300_000)), file("b.bin", bytes(300_000)))

        val capped = findDuplicates(files, deadlineMillis = 1_000, now = { 0L }, maxHashBytes = 100_000)
        assertTrue(capped.partial)
        assertTrue(capped.groups.isEmpty())

        val roomy = findDuplicates(files, deadlineMillis = 1_000, now = { 0L }, maxHashBytes = 10_000_000)
        assertFalse(roomy.partial)
        assertEquals(1, roomy.groups.size)
    }

    @Test
    fun possibleCopiesTellsConfirmedFromUntold() {
        val a = file("a.bin", bytes(300_000))
        val b = file("b.bin", bytes(300_000))
        val c = file("c.bin", bytes(300_000, seed = 2))
        val sized = listOf(a, b, c).map { it to it.length() }

        val told = possibleCopies(sized, Long.MAX_VALUE, { 0L }, Long.MAX_VALUE)
        assertEquals(listOf(listOf(a, b)), told.identical)
        assertTrue(told.untold.isEmpty())

        // Out of budget, every same-size file counts as a possible copy, so a caller keeps one.
        val untold = possibleCopies(sized, Long.MAX_VALUE, { 0L }, maxHashBytes = 10)
        assertTrue(untold.identical.isEmpty())
        assertEquals(listOf(listOf(a, b, c)), untold.untold)
    }
}
