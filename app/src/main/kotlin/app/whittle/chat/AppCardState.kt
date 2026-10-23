package app.whittle.chat

import app.whittle.apps.InstallResult
import app.whittle.apps.PermissionWords
import app.whittle.apps.PowerfulDeclaration
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/** Which buttons an app card offers, from the version of the app that is installed. */
enum class AppCardState { INSTALL, UPDATE, OPEN, NEWER_INSTALLED }

/** What an app card shows about its install; [installed] is null until read or when the app isn't installed. */
data class AppUiState(
    val installed: Int? = null,
    val installing: Boolean = false,
    val result: InstallResult? = null,
    /** The installer's session for this install, once there is one. */
    val sessionId: Int? = null,
)

/**
 * Whether a card marked installing still is. It stops once the build is installed, or once its session is gone (the system
 * never reported back). With no session yet the APK is still being checked.
 */
fun stillInstalling(installing: Boolean, installed: Int?, cardVersion: Int, sessionId: Int?, liveSessions: Set<Int>): Boolean =
    installing && installed != cardVersion && (sessionId == null || sessionId in liveSessions)

/** The card always stands for its own build: an installed build that is older gets Update, a newer one is never replaced. */
fun appCardState(card: ThreadItem.App, installed: Int?): AppCardState = when {
    installed == null -> AppCardState.INSTALL
    installed < card.versionCode -> AppCardState.UPDATE
    installed == card.versionCode -> AppCardState.OPEN
    else -> AppCardState.NEWER_INSTALLED
}

/** The card's APK in the workspace, or null when its path would leave [root]. */
fun resolveApk(root: File, apkPath: String): File? {
    val file = File(root, apkPath)
    val base = root.canonicalFile
    val resolved = file.canonicalFile
    return file.takeIf { !File(apkPath).isAbsolute && resolved.path.startsWith(base.path + File.separator) }
}

/**
 * The powerful declarations the card's build has that the installed build lacks, compared by name and kind. [installed] is what
 * the installed app counts as powerful (null when it isn't installed), so a permission of the other kind never hides one.
 */
fun addedPowerful(card: ThreadItem.App, installed: List<PowerfulDeclaration>?): List<PowerfulDeclaration> {
    val have = installed.orEmpty().map { it.name to it.kind }.toSet()
    return if (installed == null) card.powerful else card.powerful.filter { (it.name to it.kind) !in have }
}

/** The permissions in plain words, each once; one that isn't in [words] shows as its constant name. */
fun permissionWords(permissions: List<String>, words: List<PermissionWords>): String {
    val byName = words.associate { it.name to it.words }
    return permissions.map { byName[it] ?: it.substringAfterLast('.') }.distinct().joinToString(", ")
}

@Serializable
private class BuiltApp(
    val app: String,
    val `package`: String,
    val versionCode: Int,
    val apk: String,
    val permissions: List<String> = emptyList(),
    val powerful: List<PowerfulDeclaration> = emptyList(),
)

private val lenient = Json { ignoreUnknownKeys = true }

/** The card for a successful `build_app` result, or null when the text isn't one. */
fun appCardFromBuild(key: String, result: String, tainted: Boolean): ThreadItem.App? = try {
    val built = lenient.decodeFromString(BuiltApp.serializer(), result)
    ThreadItem.App(key, built.app, built.`package`, built.versionCode, built.apk, built.permissions, built.powerful, tainted)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
