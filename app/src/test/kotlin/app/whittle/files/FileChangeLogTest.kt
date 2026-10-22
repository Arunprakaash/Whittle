package app.whittle.files

import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.FileChanges
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileChangeLogTest {
    private val base = Files.createTempDirectory("whittle-changes").toFile()
    private val workspace = File(base, "workspace")
    private var now = 1_000L
    private val log = FileChangeLog(File(base, "changes"), Workspaces(workspace), clock = { now })
    private val chat = WorkspaceId("chat-1")

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun `the first write keeps what the file held, later ones only count`() = runBlocking<Unit> {
        val file = write("app/main.ts", "v2")
        log.record(chat, file, FileChanges.Before.Text("v1"))
        now = 2_000L
        log.record(chat, write("app/main.ts", "v3"), FileChanges.Before.Text("v2"))
        assertEquals(listOf(FileChange("app/main.ts", created = false, baseline = "v1", lastAtMillis = 2_000L, edits = 2)), log.changes(chat))
    }

    @Test
    fun `each chat has its own record of the shared files`() = runBlocking<Unit> {
        log.record(chat, write("notes.md", "hi"), FileChanges.Before.Nothing)
        assertTrue(log.changes(WorkspaceId("chat-2")).isEmpty())
        assertTrue(log.changes(chat).single().created)
    }

    @Test
    fun `files outside the workspace and deleted files aren't listed`() = runBlocking<Unit> {
        val outside = File(base, "elsewhere.txt").apply { writeText("x") }
        log.record(chat, outside, FileChanges.Before.Nothing)
        val gone = write("gone.txt", "x")
        log.record(chat, gone, FileChanges.Before.Nothing)
        gone.delete()
        assertTrue(log.changes(chat).isEmpty())
    }

    @Test
    fun `forgetting a chat keeps its files`() = runBlocking<Unit> {
        val file = write("keep.txt", "x")
        log.record(chat, file, FileChanges.Before.Nothing)
        log.forget(chat)
        assertTrue(log.changes(chat).isEmpty())
        assertTrue(file.isFile)
    }

    private fun write(path: String, text: String): File = File(workspace, path).apply { parentFile?.mkdirs(); writeText(text) }
}
