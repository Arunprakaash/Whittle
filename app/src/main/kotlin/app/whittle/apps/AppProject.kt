package app.whittle.apps

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What a project folder records about its app, in `whittle-app.json`: the name the user sees, the package and the last built version. */
@Serializable
data class AppProject(val name: String, val packageName: String, val versionCode: Int)

/** Reads and writes a project folder's [AppProject]. */
object AppProjects {
    const val FILE = "whittle-app.json"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** The project in [folder], or null when the file is missing or can't be read. */
    fun read(folder: File): AppProject? = try {
        json.decodeFromString(AppProject.serializer(), File(folder, FILE).readText())
    } catch (_: Exception) {
        null
    }

    fun write(folder: File, project: AppProject) {
        File(folder, FILE).writeText(json.encodeToString(AppProject.serializer(), project) + "\n")
    }
}
