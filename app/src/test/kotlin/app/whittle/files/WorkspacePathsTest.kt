package app.whittle.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspacePathsTest {
    @Test
    fun `linux paths under the mount and relative paths land in the workspace`() {
        assertEquals("tip-splitter/src/App.tsx", workspacePath("/workspace/tip-splitter/src/App.tsx", MOUNT))
        assertEquals("notes/plan.md", workspacePath("./notes//plan.md", MOUNT))
    }

    @Test
    fun `paths outside the workspace have no place in it`() {
        assertNull(workspacePath("/root/.bashrc", MOUNT))
        assertNull(workspacePath("/workspace", MOUNT))
        assertNull(workspacePath("../escape.txt", MOUNT))
    }

    private companion object {
        const val MOUNT = "/workspace"
    }
}
