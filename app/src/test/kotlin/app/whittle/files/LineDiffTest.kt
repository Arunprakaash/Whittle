package app.whittle.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineDiffTest {
    @Test
    fun `a changed line shows as removed then added, numbered as the file is now`() {
        val lines = LineDiff.diff(listOf("a", "b", "c"), listOf("a", "B", "c"), MAX_CELLS)
        assertEquals(
            listOf(DiffLine.Same(1, "a"), DiffLine.Removed("b"), DiffLine.Added(2, "B"), DiffLine.Same(3, "c")),
            lines,
        )
    }

    @Test
    fun `lines added in the middle keep the rest matched`() {
        val lines = LineDiff.diff(listOf("x", "y", "z"), listOf("x", "new 1", "y", "new 2", "z"), MAX_CELLS)
        assertEquals(
            listOf(DiffLine.Same(1, "x"), DiffLine.Added(2, "new 1"), DiffLine.Same(3, "y"), DiffLine.Added(4, "new 2"), DiffLine.Same(5, "z")),
            lines,
        )
    }

    @Test
    fun `a new file is all added`() {
        assertEquals(listOf(DiffLine.Added(1, "one"), DiffLine.Added(2, "two")), LineDiff.diff(emptyList(), listOf("one", "two"), MAX_CELLS))
    }

    @Test
    fun `past the cell limit the middle is replaced whole`() {
        val lines = LineDiff.diff(listOf("a", "b", "c"), listOf("c", "b", "a"), maxCells = 4)
        assertEquals(listOf("a", "b", "c"), lines.filterIsInstance<DiffLine.Removed>().map { it.text })
        assertEquals(listOf("c", "b", "a"), lines.filterIsInstance<DiffLine.Added>().map { it.text })
    }

    @Test
    fun `folding keeps context around changes and counts the rest`() {
        val before = (1..20).map { "line $it" }
        val after = before.toMutableList().apply { this[9] = "changed" }
        val folded = LineDiff.fold(LineDiff.diff(before, after, MAX_CELLS), context = 2)
        assertEquals(DiffLine.Folded(7), folded.first())
        assertEquals(DiffLine.Folded(8), folded.last())
        assertTrue(DiffLine.Added(10, "changed") in folded)
        assertEquals(5 + 1 + 2, folded.size, "2 lines each side, the change as removed and added, and two folds")
    }

    @Test
    fun `nothing changed folds to nothing`() {
        assertEquals(emptyList(), LineDiff.fold(LineDiff.diff(listOf("same"), listOf("same"), MAX_CELLS), context = 3))
    }

    private companion object {
        const val MAX_CELLS = 10_000
    }
}
