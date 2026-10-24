import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Turns the pinned Termux packages into what the app ships, for one build variant:
 * - every program and library (ELF) as a native library, because Android only lets apps run native code
 *   from the APK's native-library folder, never from their own storage;
 * - everything else (JavaScript, Python, certificates, configuration) in one archive asset;
 * - a manifest saying where each file, native library and link goes, so the app can lay the prefix out.
 * Termux compiles its own prefix into its programs; every file is rewritten to this app's prefix, which is
 * padded to exactly the same length so binaries stay intact.
 */
@CacheableTask
abstract class TermuxRuntimeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockFile: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    /** Folder under the assets root the app reads the manifest and archive from. */
    @get:Input
    abstract val assetDirectory: Property<String>

    /** Downloaded packages, shared by variants and kept across clean builds; each is checked against the lock. */
    @get:Internal
    abstract val downloadDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val spec = TermuxSpec.read(specFile.get().asFile)
        val lock = Lockfile.read(lockFile.get().asFile)
        val prefix = spec.prefixFor(applicationId.get())
        val jniRoot = jniLibsDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        val assetsRoot = assetsDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        spec.abis.keys.forEach { abi ->
            val packages = lock[abi] ?: throw GradleException("The lock file has no $abi packages; run ./gradlew :app:updateTermuxLock")
            val layout = Layout(spec, prefix, File(jniRoot, abi).apply { mkdirs() })
            val assets = File(assetsRoot, "${assetDirectory.get()}/$abi").apply { mkdirs() }
            ZipOutputStream(File(assets, ARCHIVE).outputStream().buffered()).use { zip ->
                packages.forEach { locked -> layout.add(entries(spec, locked), zip) }
            }
            File(assets, MANIFEST).writeText(JsonOutput.prettyPrint(JsonOutput.toJson(layout.manifest())))
            logger.lifecycle("Tools for $abi: ${layout.summary()}")
        }
    }

    private fun entries(spec: TermuxSpec, locked: LockedPackage): List<TarEntry> {
        val bytes = Debs.fetch(spec, locked, downloadDirectory.get().asFile)
        return Debs.dataEntries(bytes, "${locked.name} ${locked.version}")
    }

    private class Layout(private val spec: TermuxSpec, private val prefix: String, private val jniDirectory: File) {
        private val termuxRoot = spec.termuxPrefix.trimStart('/') + "/"
        private val excluded = spec.exclude.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        private val oldPrefix = spec.termuxPrefix.toByteArray()
        private val newPrefix = prefix.toByteArray()
        private val native = sortedMapOf<String, String>()
        private val symlinks = sortedMapOf<String, String>()
        private val directories = sortedSetOf<String>()
        private val executables = sortedSetOf<String>()
        private val written = mutableSetOf<String>()
        private var archived = 0L

        fun add(entries: List<TarEntry>, zip: ZipOutputStream) {
            entries.forEach { entry ->
                val path = relative(entry.path) ?: return@forEach
                if (isExcluded(path, entry is TarEntry.Directory) || !written.add(path)) return@forEach
                when (entry) {
                    is TarEntry.Directory -> directories += path
                    is TarEntry.Symlink -> symlinks[path] = String(patch(entry.target.toByteArray()))
                    is TarEntry.Hardlink -> relative(entry.target)?.let { symlinks[path] = "$prefix/$it" }
                    is TarEntry.Regular -> addFile(path, patch(entry.bytes), entry.executable, zip)
                }
            }
        }

        fun manifest(): Map<String, Any> {
            spec.symlinks.forEach { (path, target) -> symlinks[path] = target }
            return mapOf(
                "prefix" to prefix,
                "archive" to ARCHIVE,
                "native" to native,
                "symlinks" to symlinks,
                "executables" to executables,
                "directories" to directories.filter { directory -> written.none { it.startsWith("$directory/") } },
            )
        }

        fun summary(): String = "${native.size} native files, ${symlinks.size} links, ${archived / MEGABYTE} MB in the archive"

        private fun addFile(path: String, bytes: ByteArray, executable: Boolean, zip: ZipOutputStream) {
            when (elfType(bytes)) {
                ELF_EXECUTABLE, ELF_SHARED -> {
                    val name = "lib" + path.replace(NOT_NAME_SAFE, "_") + ".so"
                    if (native.containsValue(name)) throw GradleException("Two tool files map to the native library $name")
                    File(jniDirectory, name).writeBytes(bytes)
                    native[path] = name
                }
                null -> {
                    zip.putNextEntry(ZipEntry(path).apply { time = FIXED_TIME })
                    zip.write(bytes)
                    zip.closeEntry()
                    // Scripts need their execute bit, or the shell won't hand them to their #! interpreter.
                    if (executable) executables += path
                    archived += bytes.size
                }
                // Object files and other ELF kinds are only for building native code, which the phone doesn't do.
                else -> Unit
            }
        }

        private fun relative(archivePath: String): String? =
            archivePath.removePrefix("./").takeIf { it.startsWith(termuxRoot) }?.removePrefix(termuxRoot)?.takeIf { it.isNotEmpty() }

        /** A folder is excluded when a glob would exclude everything in it, as "include" with "include/" followed by two stars. */
        private fun isExcluded(path: String, isDirectory: Boolean): Boolean {
            val candidates = listOfNotNull(Path.of(path), if (isDirectory) Path.of(path, ANY_CHILD) else null)
            return excluded.any { matcher -> candidates.any(matcher::matches) }
        }

        /** Same-length replacement only, so offsets inside binaries never move. */
        private fun patch(bytes: ByteArray): ByteArray {
            var index = indexOf(bytes, oldPrefix, 0)
            if (index < 0) return bytes
            val copy = bytes.copyOf()
            while (index >= 0) {
                newPrefix.copyInto(copy, index)
                index = indexOf(copy, oldPrefix, index + oldPrefix.size)
            }
            return copy
        }

        private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int): Int {
            val first = pattern[0]
            var i = from
            val last = bytes.size - pattern.size
            while (i <= last) {
                if (bytes[i] == first && (1 until pattern.size).all { bytes[i + it] == pattern[it] }) return i
                i++
            }
            return -1
        }

        /** e_type of a 64- or 32-bit little-endian ELF file, or null for anything else. */
        private fun elfType(bytes: ByteArray): Int? {
            if (bytes.size < ELF_HEADER || bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null
            return (bytes[E_TYPE].toInt() and BYTE) or ((bytes[E_TYPE + 1].toInt() and BYTE) shl BITS)
        }
    }

    private companion object {
        const val ARCHIVE = "files.zip"
        const val ANY_CHILD = "child"
        const val MANIFEST = "manifest.json"
        const val FIXED_TIME = 0L
        const val MEGABYTE = 1024 * 1024
        const val ELF_HEADER = 18
        const val E_TYPE = 16
        const val ELF_EXECUTABLE = 2
        const val ELF_SHARED = 3
        const val BYTE = 0xFF
        const val BITS = 8
        val NOT_NAME_SAFE = Regex("[^A-Za-z0-9]")
    }
}
