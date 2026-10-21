package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxFileSystemTest {
    private val base = Files.createTempDirectory("whittle-linux").toFile()
    private val linuxRoot = File(base, "rootfs").apply { mkdirs() }
    private val workspace = File(base, "workspaces/chat-1").apply { mkdirs() }
    private val linux = LinuxFileSystem(root = { linuxRoot }, workspaceMount = "/workspace", deviceMounts = listOf("/proc", "/dev", "/sys"))

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun `relative paths and the mount land in the chat's folder`() {
        assertEquals(File(workspace, "src/app.js"), resolved("src/app.js"))
        assertEquals(File(workspace, "src/app.js"), resolved("/workspace/src/app.js"))
        assertEquals(workspace, resolved("/workspace"))
    }

    @Test
    fun `absolute paths land in the Linux folder, through its links`() {
        File(linuxRoot, "usr/bin").mkdirs()
        link(File(linuxRoot, "bin"), "usr/bin")
        File(linuxRoot, "etc").mkdirs()
        link(File(linuxRoot, "etc/alternatives"), "/usr/bin")
        assertEquals(File(linuxRoot, "usr/bin/bash"), resolved("/bin/bash"))
        assertEquals(File(linuxRoot, "usr/bin/node"), resolved("/etc/alternatives/node"))
    }

    @Test
    fun `nothing climbs out of Linux`() {
        assertEquals(File(linuxRoot, "etc/hosts"), resolved("/../../etc/hosts"))
        assertEquals(File(linuxRoot, "etc/hosts"), resolved("../../../etc/hosts"))
        link(File(workspace, "escape"), "/etc/passwd")
        assertEquals(File(linuxRoot, "etc/passwd"), resolved("escape"), "an absolute link target starts at Linux's root, not the phone's")
    }

    @Test
    fun `proot's hard-link stand-ins point back into Linux`() {
        File(linuxRoot, "usr/bin").mkdirs()
        val hidden = File(linuxRoot, "usr/bin/.l2s.gunzip0001").apply { writeText("#!/bin/sh") }
        link(File(linuxRoot, "usr/bin/gunzip"), hidden.path)
        assertEquals(hidden, resolved("/usr/bin/gunzip"))
    }

    @Test
    fun `live device views and link loops are refused`() {
        assertTrue(failed("/proc/self/status"))
        assertTrue(failed("/dev/null"))
        link(File(workspace, "a"), "b")
        link(File(workspace, "b"), "a")
        assertTrue(failed("a"))
    }

    @Test
    fun `before Linux is set up only the chat's folder resolves`() {
        val notSetUp = LinuxFileSystem(root = { null }, workspaceMount = "/workspace", deviceMounts = emptyList())
        assertEquals(File(workspace, "notes.txt"), (notSetUp.resolve(workspace, "notes.txt") as Outcome.Success).value)
        assertTrue(notSetUp.resolve(workspace, "/etc/hosts") is Outcome.Failure)
        link(File(workspace, "out"), "/etc/hosts")
        assertTrue(notSetUp.resolve(workspace, "out") is Outcome.Failure)
    }

    private fun resolved(path: String): File = when (val result = linux.resolve(workspace, path)) {
        is Outcome.Success -> result.value
        is Outcome.Failure -> error("$path: ${result.error.message}")
    }

    private fun failed(path: String): Boolean = linux.resolve(workspace, path) is Outcome.Failure

    private fun link(at: File, target: String) {
        Files.createSymbolicLink(at.toPath(), File(target).toPath())
    }
}
