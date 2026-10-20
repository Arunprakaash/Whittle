package app.whittle.export

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What one exported app is: Android identity plus the built web app's files (path inside the app to file). */
class AppBundle(
    val packageName: String,
    val label: String,
    val iconPng: ByteArray,
    val files: Map<String, File>,
)

/**
 * Builds an unsigned APK from the shell: its manifest gets the app's package and name, its icon is replaced,
 * and the web app goes into assets/www/. Old signatures are dropped; [AppSigner] signs the result.
 */
class AppPackager(private val packagePlaceholder: String, private val labelPlaceholder: String) {
    fun pack(shell: InputStream, app: AppBundle, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            ZipInputStream(shell).use { source ->
                var iconReplaced = false
                generateSequence { source.nextEntry }.forEach { entry ->
                    val name = entry.name
                    if (name.startsWith(SIGNATURE_DIRECTORY) || entry.isDirectory) return@forEach
                    val bytes = source.readBytes()
                    val content = when {
                        name == MANIFEST -> BinaryXmlStrings.replace(bytes, mapOf(packagePlaceholder to app.packageName, labelPlaceholder to app.label))
                        // The shell's only image is its launcher icon (the build may have renamed it).
                        name.startsWith(RESOURCES) && name.endsWith(PNG) -> app.iconPng.also { iconReplaced = true }
                        else -> bytes
                    }
                    // Android maps resources.arsc straight from the APK, so it must stay uncompressed.
                    write(zip, name, content, stored = name == RESOURCE_TABLE)
                }
                check(iconReplaced) { "the shell APK has no launcher icon to replace" }
            }
            app.files.toSortedMap().forEach { (path, file) -> write(zip, WEB_ROOT + path, file.readBytes(), stored = false) }
        }
    }

    private fun write(zip: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val entry = ZipEntry(name).apply {
            time = FIXED_TIME
            if (stored) {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    companion object {
        /** Where the shell looks for the app (see export/shell). */
        const val WEB_ROOT = "assets/www/"
        private const val MANIFEST = "AndroidManifest.xml"
        private const val RESOURCE_TABLE = "resources.arsc"
        private const val RESOURCES = "res/"
        private const val PNG = ".png"
        private const val SIGNATURE_DIRECTORY = "META-INF/"

        /** Same bytes for the same app, whenever it's exported. */
        private const val FIXED_TIME = 315_532_800_000L
    }
}
