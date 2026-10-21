package app.whittle.runtimes

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import app.whittle.settings.ToolRuntimeDefaults
import app.whittle.tools.workspace.ShellSetup
import app.whittle.tools.workspace.ShellTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** What the build packed for one ABI (see build-logic TermuxRuntimeTask). */
@Serializable
internal data class RuntimeManifest(
    /** Where the tools must live: their programs were built for exactly this path. */
    val prefix: String,
    val archive: String,
    /** Prefix path to the native library holding that program or library. */
    val native: Map<String, String>,
    /** Prefix path to link target. */
    val symlinks: Map<String, String>,
    val directories: List<String>,
    /** Archive files to mark executable: scripts the shell runs through their #! interpreter. */
    val executables: List<String>,
)

/**
 * Lays out the programs that ship in the APK to start and unpack Linux (proot, tar) the first time they're needed,
 * and again after an update changes them. Programs and libraries stay in the APK's native-library folder, the only
 * place Android lets an app run native code from; the prefix holds links to them, plus their data files. They then
 * run with the environment from [config].
 */
class ToolRuntime(
    private val context: Context,
    private val config: ToolRuntimeDefaults,
    private val systemPath: String,
    private val assetDirectory: String,
) : ShellTools {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var ready: ShellSetup? = null

    override suspend fun setup(): ShellSetup {
        ready?.let { return it }
        return lock.withLock {
            ready ?: withContext(Dispatchers.IO) { install() }?.also { ready = it } ?: ShellSetup.NONE
        }
    }

    /** Null when the programs can't be set up; Linux can't start then, and the next use tries again. */
    private fun install(): ShellSetup? = try {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { abi -> context.assets.list("$assetDirectory/$abi").orEmpty().isNotEmpty() }
        if (abi == null) {
            null
        } else {
            val manifestBytes = context.assets.open("$assetDirectory/$abi/$MANIFEST").use { it.readBytes() }
            val manifest = json.decodeFromString(RuntimeManifest.serializer(), manifestBytes.decodeToString())
            val prefix = File(manifest.prefix)
            if (!sameFolder(prefix.parentFile, context.dataDir)) {
                // A work profile or second user has a different data folder; the tools only fit the main one.
                Log.w(TAG, "Tools are built for ${manifest.prefix}, outside ${context.dataDir}")
                null
            } else {
                // Android moves the native-library folder on every install and update, so the links are redone then too.
                val layout = sha256(manifestBytes) + "\n" + context.applicationInfo.nativeLibraryDir
                val marker = File(prefix, LAYOUT_MARKER)
                if (!marker.isFile || marker.readText() != layout) layOut(abi, manifest, prefix, marker, layout)
                setup(prefix)
            }
        }
    } catch (exception: IOException) {
        Log.w(TAG, "Couldn't set up the command-line tools", exception)
        null
    } catch (exception: ErrnoException) {
        Log.w(TAG, "Couldn't set up the command-line tools", exception)
        null
    } catch (exception: SerializationException) {
        Log.w(TAG, "The tools manifest is damaged", exception)
        null
    }

    private fun layOut(abi: String, manifest: RuntimeManifest, prefix: File, marker: File, layout: String) {
        deleteTree(prefix)
        val root = prefix.apply { mkdirs() }.canonicalFile
        ZipInputStream(context.assets.open("$assetDirectory/$abi/${manifest.archive}").buffered()).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                val target = inside(root, entry.name)
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        manifest.executables.forEach { inside(root, it).setExecutable(true, true) }
        manifest.directories.forEach { inside(root, it).mkdirs() }
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
        manifest.native.forEach { (path, library) -> link("$nativeDirectory/$library", inside(root, path)) }
        manifest.symlinks.forEach { (path, target) -> link(target, inside(root, path)) }
        marker.writeText(layout)
    }

    private fun setup(prefix: File): ShellSetup {
        val cache = File(context.cacheDir, config.cacheDirectoryName).apply { mkdirs() }
        val variables = mapOf(
            PREFIX to prefix.path,
            DATA_DIR to (prefix.parent ?: prefix.path),
            CACHE_DIR to cache.path,
            SYSTEM_PATH to systemPath,
        )
        return ShellSetup(
            environment = config.environment.mapValues { (name, value) -> render("toolRuntime.environment.$name", value, variables) },
            functions = emptyList(),
        )
    }

    private fun render(name: String, template: String, variables: Map<String, String>): String =
        when (val rendered = PromptTemplate(ConfigName(name), template).render(variables)) {
            is Outcome.Success -> rendered.value
            is Outcome.Failure -> throw IOException(rendered.error.message)
        }

    private fun link(target: String, path: File) {
        path.parentFile?.mkdirs()
        if (path.exists() || Files.isSymbolicLink(path.toPath())) path.delete()
        Os.symlink(target, path.path)
    }

    /** A path from the manifest or archive, refused if it would land outside the prefix. */
    private fun inside(root: File, path: String): File {
        val file = File(root, path)
        if (path.startsWith("/") || path.split('/').any { it == ".." }) throw IOException("Unsafe tool path $path")
        return file
    }

    /** Deletes without following links, so a link to /system or the APK is removed, never what it points at. */
    private fun deleteTree(directory: File) {
        val start = directory.toPath()
        if (!Files.exists(start, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(start).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /**
     * Whether two paths name the same folder. Compared by device and inode, since the data folder has more than one
     * path (/data/data/<package> and /data/user/0/<package>) and which one the app is given depends on its target SDK.
     */
    private fun sameFolder(a: File?, b: File): Boolean = a != null && try {
        val left = Os.stat(a.path)
        val right = Os.stat(b.path)
        left.st_dev == right.st_dev && left.st_ino == right.st_ino
    } catch (exception: ErrnoException) {
        false
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance(SHA_256).digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "ToolRuntime"
        const val SHA_256 = "SHA-256"
        const val MANIFEST = "manifest.json"
        const val LAYOUT_MARKER = ".whittle-tools-layout"
        const val PREFIX = "prefix"
        const val DATA_DIR = "dataDir"
        const val CACHE_DIR = "cacheDir"
        const val SYSTEM_PATH = "systemPath"
    }
}
