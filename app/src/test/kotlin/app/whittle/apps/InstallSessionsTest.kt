package app.whittle.apps

import app.whittle.core.common.EpochClock
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallSessionsTest {
    private val directory = Files.createTempDirectory("install-sessions").toFile()
    private val file = File(directory, "install-sessions.json")
    private var now = 1_000_000L
    private val clock = EpochClock { now }

    private val lister = object : SessionLister {
        val live = mutableSetOf<Int>()
        val abandoned = mutableListOf<Int>()
        override fun live(): Set<Int> = live.toSet()
        override fun abandon(id: Int) {
            abandoned += id
            live -= id
        }
    }

    private val cleared = mutableListOf<Int>()

    private fun sessions() = InstallSessions(file, clock, STALE_MILLIS, lister) { cleared += it }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `a fresh session is kept, even by a new process`() {
        sessions().started(1)
        lister.live += 1
        now += STALE_MILLIS - 1
        assertEquals(setOf(1), sessions().cleanUp())
        assertTrue(lister.abandoned.isEmpty())
    }

    @Test
    fun `a stale session is abandoned`() {
        sessions().started(2)
        lister.live += 2
        now += STALE_MILLIS + 1
        assertEquals(emptySet(), sessions().cleanUp())
        assertEquals(listOf(2), lister.abandoned)
        assertEquals(listOf(2), cleared, "an abandoned session's prompt notification goes too")
    }

    @Test
    fun `a session with no record, from an older build, is abandoned`() {
        sessions().started(1)
        lister.live += setOf(1, 3)
        assertEquals(setOf(1), sessions().cleanUp())
        assertEquals(listOf(3), lister.abandoned)
        assertEquals(listOf(3), cleared, "only the abandoned session's notification is cleared")
    }

    @Test
    fun `an ended session's record goes`() {
        val sessions = sessions()
        sessions.started(1)
        sessions.ended(1)
        lister.live += 1
        assertEquals(emptySet(), sessions.cleanUp())
        assertEquals(listOf(1), lister.abandoned)
    }

    @Test
    fun `clean-up drops the records of sessions that are no longer open`() {
        sessions().started(2)
        assertEquals(emptySet(), sessions().cleanUp())
        lister.live += 2
        assertEquals(emptySet(), sessions().cleanUp())
        assertEquals(listOf(2), lister.abandoned)
    }

    @Test
    fun `a damaged record file counts as no records`() {
        file.writeText("{nope")
        lister.live += 4
        assertEquals(emptySet(), sessions().cleanUp())
        assertEquals(listOf(4), lister.abandoned)
    }

    private companion object {
        const val STALE_MILLIS = 30 * 60_000L
    }
}
