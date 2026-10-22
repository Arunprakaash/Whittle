package app.whittle.tools.workspace

import app.whittle.tools.api.WorkspaceId

/**
 * The project each chat works in: a folder at the top of the shared workspace, or none for the whole workspace.
 * Commands start in it and relative paths resolve inside it. The app keeps the choices.
 */
interface ChatProjects {
    fun projectOf(chat: WorkspaceId): String?

    /** Sets [chat]'s project; null for the whole workspace. Takes effect at once, for the next command. */
    fun choose(chat: WorkspaceId, project: String?)

    companion object {
        val NONE = object : ChatProjects {
            override fun projectOf(chat: WorkspaceId): String? = null

            override fun choose(chat: WorkspaceId, project: String?) = Unit
        }

        private val NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$""")

        /** A plain folder name: letters, digits, dots, dashes and underscores, not starting with a dot. */
        fun isValidName(name: String): Boolean = NAME.matches(name)
    }
}
