package app.whittle.apps

import app.whittle.core.common.EpochClock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Whittle's open install sessions, as Android's PackageInstaller lists them. */
interface SessionLister {
    fun live(): Set<Int>

    fun abandon(id: Int)
}

/**
 * When each of Whittle's install sessions began, kept by session id in [file] in Whittle's private storage, so that a launch
 * abandons only stale sessions. Android may kill Whittle's process while its install prompt is on screen and something may
 * start it again; the session the user is about to confirm is then young and is kept. A session with no record came from a
 * build before records were kept, and is abandoned.
 */
class InstallSessions(
    private val file: File,
    private val clock: EpochClock,
    /** Older than this, a session is abandoned at launch; longer than the install prompt's own timeout. */
    private val staleAfterMillis: Long,
    private val lister: SessionLister,
) {
    private val lock = Any()

    /** Notes that session [id] began now. */
    fun started(id: Int) = change { it + (id to clock.nowMillis()) }

    /** Forgets session [id]: it ended, one way or another. */
    fun ended(id: Int) = change { it - id }

    /**
     * Abandons the open sessions that are stale or have no record, drops the records of sessions no longer open, and returns
     * the sessions kept.
     */
    fun cleanUp(): Set<Int> = synchronized(lock) {
        val started = read()
        val now = clock.nowMillis()
        val (kept, stale) = lister.live().partition { id -> started[id]?.let { now - it <= staleAfterMillis } ?: false }
        stale.forEach(lister::abandon)
        write(started.filterKeys { it in kept })
        kept.toSet()
    }

    private fun change(edit: (Map<Int, Long>) -> Map<Int, Long>) {
        synchronized(lock) { write(edit(read())) }
    }

    private fun read(): Map<Int, Long> = try {
        if (file.isFile) Json.decodeFromString(SERIALIZER, file.readText()) else emptyMap()
    } catch (_: IOException) {
        emptyMap()
    } catch (_: IllegalArgumentException) {
        emptyMap()
    }

    /** Writes a temporary file and renames it over [file], so a kill in the middle leaves the old one whole. */
    private fun write(started: Map<Int, Long>) {
        try {
            val temporary = File(file.parentFile, file.name + TEMPORARY)
            temporary.writeText(Json.encodeToString(SERIALIZER, started))
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: IOException) {
            // Without a record the session reads as unknown at the next launch, and is abandoned then: the old behaviour.
        }
    }

    companion object {
        const val FILE_NAME = "install-sessions.json"
        private const val TEMPORARY = ".tmp"
        private val SERIALIZER = MapSerializer(Int.serializer(), Long.serializer())
    }
}
