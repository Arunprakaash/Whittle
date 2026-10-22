package app.whittle.files

/** A project: a folder at the top of the workspace, and when anything in it last changed. */
data class ProjectFolder(val name: String, val changedAtMillis: Long)
