package app.whittle.files

/**
 * A path the agent gave a file tool, relative to the workspace, or null when it points outside it. Linux paths
 * under [mount] lose it; relative paths are relative to the workspace already.
 */
fun workspacePath(agentPath: String, mount: String): String? {
    val path = agentPath.trim()
    val relative = when {
        path == mount -> return null
        path.startsWith("$mount/") -> path.removePrefix("$mount/")
        path.startsWith("/") -> return null
        else -> path
    }
    val parts = relative.split('/').filter { it.isNotEmpty() && it != "." }
    return parts.takeIf { it.isNotEmpty() && ".." !in it }?.joinToString("/")
}
