package app.whittle.files

import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatProjectStoreTest {
    private val base = Files.createTempDirectory("whittle-projects").toFile()
    private val file = File(base, "chat-projects.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        base.deleteRecursively()
    }

    @Test
    fun `a choice applies at once and is there after a restart`() = runBlocking<Unit> {
        val store = ChatProjectStore(file, scope)
        store.choose(CHAT, "tip-splitter")
        assertEquals("tip-splitter", store.projectOf(CHAT))
        settle()
        assertEquals("tip-splitter", ChatProjectStore(file, scope).projectOf(CHAT))
    }

    @Test
    fun `forgetting a chat sends it back to the whole workspace`() = runBlocking<Unit> {
        val store = ChatProjectStore(file, scope)
        store.choose(CHAT, "notes")
        store.forget(CHAT)
        settle()
        assertNull(ChatProjectStore(file, scope).projectOf(CHAT))
    }

    @Test
    fun `a confined chat stays confined after a restart, until the user chooses a project`() = runBlocking<Unit> {
        val store = ChatProjectStore(file, scope)
        store.confine(CHAT, "brief")
        settle()
        val restarted = ChatProjectStore(file, scope)
        assertEquals("brief", restarted.projectOf(CHAT))
        assertTrue(restarted.isConfined(CHAT))
        restarted.choose(CHAT, "notes")
        assertFalse(restarted.isConfined(CHAT))
    }

    @Test
    fun `reads the projects a version without confinement wrote`() = runBlocking<Unit> {
        file.writeText("""{"chat-1":"tip-splitter"}""")
        val store = ChatProjectStore(file, scope)
        assertEquals("tip-splitter", store.projectOf(CHAT))
        assertFalse(store.isConfined(CHAT))
    }

    /** Writes happen right after a choice, off the caller's thread. */
    private suspend fun settle() = delay(300)

    private companion object {
        val CHAT = WorkspaceId("chat-1")
    }
}
