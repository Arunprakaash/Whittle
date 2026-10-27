package app.whittle.tools.android

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Groups of identical files; [partial] means time or the hashing budget ran out, so more may exist. */
data class Duplicates(val groups: List<List<File>>, val partial: Boolean)

/** Identical files, grouped, with no limits; see [findDuplicates] for the version that keeps a budget. */
fun duplicateGroups(files: List<File>): List<List<File>> = findDuplicates(files, Long.MAX_VALUE, { 0L }, Long.MAX_VALUE).groups

/**
 * Groups of byte-identical files in [files], found the cheap way round: only files sharing a size are looked at,
 * then the SHA-256 of the first and last 64 KiB, then the full SHA-256 for what still collides. Empty files are
 * skipped and unreadable ones left out. Reading stops when [now] reaches [deadlineMillis] (checked between chunks) or
 * after [maxHashBytes] have been read in all, and the result is then partial: groups already confirmed are kept, but
 * a file is never reported as a copy on a hash that wasn't finished.
 */
fun findDuplicates(files: List<File>, deadlineMillis: Long, now: () -> Long, maxHashBytes: Long): Duplicates {
    val budget = HashBudget(deadlineMillis, now, maxHashBytes)
    val groups = mutableListOf<List<File>>()
    val bySize = files.map { it to it.length() }.filter { it.second > 0 }.groupBy({ it.second }, { it.first })
    for ((size, sameSize) in bySize) {
        if (sameSize.size < 2) continue
        if (budget.stopped) break
        val byEdges = sameSize.groupByHash { edgesHash(it, size, budget) }
        for (collided in byEdges) {
            // Up to two edges' worth the edge hash has already read the whole file, so it is conclusive.
            if (size <= EDGE_BYTES * 2) {
                groups += collided
                continue
            }
            groups += collided.groupByHash { fullHash(it, budget) }
        }
    }
    return Duplicates(groups, budget.stopped)
}

/**
 * Files among [files] (each with its size in bytes) that may hold the same content, for keeping one of each: [identical]
 * groups are confirmed by hash, and [untold] groups share a size but couldn't be told apart, because time or the hashing
 * budget ran out or a file couldn't be read. Empty files are left out: deleting every copy of nothing loses nothing.
 */
data class PossibleCopies(val identical: List<List<File>>, val untold: List<List<File>>)

/** [PossibleCopies] among [files], hashing them as [findDuplicates] does, within the same kind of budget. */
fun possibleCopies(files: List<Pair<File, Long>>, deadlineMillis: Long, now: () -> Long, maxHashBytes: Long): PossibleCopies {
    val budget = HashBudget(deadlineMillis, now, maxHashBytes)
    val identical = mutableListOf<List<File>>()
    val untold = mutableListOf<List<File>>()
    val bySize = files.filter { it.second > 0 }.groupBy({ it.second }, { it.first })
    for ((size, sameSize) in bySize) {
        if (sameSize.size < 2) continue
        val edges = sameSize.map { it to edgesHash(it, size, budget) }
        if (edges.any { it.second == null }) {
            untold += sameSize
            continue
        }
        for (collided in edges.groupBy({ it.second }, { it.first }).values.filter { it.size > 1 }) {
            // Up to two edges' worth the edge hash has already read the whole file, so it is conclusive.
            if (size <= EDGE_BYTES * 2) {
                identical += collided
                continue
            }
            val full = collided.map { it to fullHash(it, budget) }
            if (full.any { it.second == null }) untold += collided else identical += full.groupBy({ it.second }, { it.first }).values.filter { it.size > 1 }
        }
    }
    return PossibleCopies(identical, untold)
}

private const val EDGE_BYTES = 64 * 1024
private const val CHUNK_BYTES = 1024 * 1024

/** Files that share a hash, two or more at a time; a file whose hash is null (unreadable or cut short) drops out. */
private fun List<File>.groupByHash(hash: (File) -> String?): List<List<File>> =
    mapNotNull { file -> hash(file)?.let { it to file } }.groupBy({ it.first }, { it.second }).values.filter { it.size > 1 }

private class HashBudget(private val deadlineMillis: Long, private val now: () -> Long, private var bytesLeft: Long) {
    var stopped = false
        private set

    /** How many of [wanted] bytes may be read now; 0 once time or the byte cap is up. */
    fun allow(wanted: Int): Int {
        if (stopped) return 0
        if (now() >= deadlineMillis || bytesLeft <= 0) {
            stopped = true
            return 0
        }
        val granted = minOf(wanted.toLong(), bytesLeft).toInt()
        bytesLeft -= granted
        return granted
    }
}

private fun edgesHash(file: File, size: Long, budget: HashBudget): String? = digest(file, budget) { input, digest ->
    val edge = minOf(EDGE_BYTES.toLong(), size)
    feed(input, 0, edge, digest, budget) && feed(input, size - edge, edge, digest, budget)
}

private fun fullHash(file: File, budget: HashBudget): String? = digest(file, budget) { input, digest ->
    feed(input, 0, input.length(), digest, budget)
}

private fun digest(file: File, budget: HashBudget, read: (RandomAccessFile, MessageDigest) -> Boolean): String? = try {
    RandomAccessFile(file, "r").use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        if (read(input, digest)) digest.digest().joinToString("") { "%02x".format(it) } else null
    }
} catch (_: IOException) {
    null
}

/** Feeds [length] bytes from [from] to [digest] in chunks, asking [budget] before each; false when it said stop or the file was shorter. */
private fun feed(input: RandomAccessFile, from: Long, length: Long, digest: MessageDigest, budget: HashBudget): Boolean {
    input.seek(from)
    val buffer = ByteArray(CHUNK_BYTES)
    var left = length
    while (left > 0) {
        val allowed = budget.allow(minOf(left, CHUNK_BYTES.toLong()).toInt())
        if (allowed == 0) return false
        val read = input.read(buffer, 0, allowed)
        if (read <= 0) return false
        digest.update(buffer, 0, read)
        left -= read
    }
    return true
}
