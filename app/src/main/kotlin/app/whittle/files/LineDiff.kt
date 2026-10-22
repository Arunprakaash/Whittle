package app.whittle.files

/** One line of a file's changes. Numbers are the line's place in the file as it is now. */
sealed interface DiffLine {
    data class Same(val number: Int, val text: String) : DiffLine

    data class Added(val number: Int, val text: String) : DiffLine

    data class Removed(val text: String) : DiffLine

    /** Unchanged lines left out between changes. */
    data class Folded(val lines: Int) : DiffLine
}

/** Line-by-line changes between two versions of a text file. */
object LineDiff {
    /**
     * Every line of [after] and the lines of [before] it lost, in order. Unchanged lines at both ends are matched
     * first; past [maxCells] line pairs in between, that middle shows as all removed, then all added.
     */
    fun diff(before: List<String>, after: List<String>, maxCells: Int): List<DiffLine> {
        var start = 0
        while (start < before.size && start < after.size && before[start] == after[start]) start++
        var endBefore = before.size
        var endAfter = after.size
        while (endBefore > start && endAfter > start && before[endBefore - 1] == after[endAfter - 1]) {
            endBefore--
            endAfter--
        }
        val lines = ArrayList<DiffLine>(after.size + (endBefore - start))
        for (index in 0 until start) lines += DiffLine.Same(index + 1, after[index])
        middle(before.subList(start, endBefore), after.subList(start, endAfter), start, maxCells, lines)
        for (index in endAfter until after.size) lines += DiffLine.Same(index + 1, after[index])
        return lines
    }

    /** Keeps [context] unchanged lines around each change and folds the rest. Nothing changed: nothing to show. */
    fun fold(lines: List<DiffLine>, context: Int): List<DiffLine> {
        val changed = lines.indices.filter { lines[it] !is DiffLine.Same }
        if (changed.isEmpty()) return emptyList()
        val keep = BooleanArray(lines.size)
        changed.forEach { index -> for (near in (index - context)..(index + context)) if (near in lines.indices) keep[near] = true }
        val folded = ArrayList<DiffLine>()
        var skipped = 0
        lines.forEachIndexed { index, line ->
            if (keep[index]) {
                if (skipped > 0) folded += DiffLine.Folded(skipped)
                skipped = 0
                folded += line
            } else {
                skipped++
            }
        }
        if (skipped > 0) folded += DiffLine.Folded(skipped)
        return folded
    }

    /** Longest common subsequence of the changed middle, walked from the front. */
    private fun middle(before: List<String>, after: List<String>, offset: Int, maxCells: Int, into: MutableList<DiffLine>) {
        val rows = before.size
        val columns = after.size
        if (rows.toLong() * columns > maxCells) {
            before.forEach { into += DiffLine.Removed(it) }
            after.forEachIndexed { index, text -> into += DiffLine.Added(offset + index + 1, text) }
            return
        }
        val width = columns + 1
        val common = IntArray((rows + 1) * width)
        for (row in rows - 1 downTo 0) {
            for (column in columns - 1 downTo 0) {
                common[row * width + column] = if (before[row] == after[column]) {
                    common[(row + 1) * width + column + 1] + 1
                } else {
                    maxOf(common[(row + 1) * width + column], common[row * width + column + 1])
                }
            }
        }
        var row = 0
        var column = 0
        while (row < rows || column < columns) {
            when {
                row < rows && column < columns && before[row] == after[column] -> {
                    into += DiffLine.Same(offset + column + 1, after[column])
                    row++
                    column++
                }
                // On a tie the old line goes first, so a replaced line reads as removed, then added.
                row < rows && (column == columns || common[(row + 1) * width + column] >= common[row * width + column + 1]) -> {
                    into += DiffLine.Removed(before[row])
                    row++
                }
                else -> {
                    into += DiffLine.Added(offset + column + 1, after[column])
                    column++
                }
            }
        }
    }
}
