import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Copies files out of one pinned npm package into generated app assets. The tarball must match the
 * registry's sha512 integrity string, so a changed or tampered package fails the build instead of shipping.
 */
@CacheableTask
abstract class NpmPackageAssetsTask : DefaultTask() {
    @get:Input
    abstract val registry: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val version: Property<String>

    /** The package's `dist.integrity` from the registry, e.g. "sha512-…". */
    @get:Input
    abstract val integrity: Property<String>

    /** Path inside the package (without the tarball's "package/" folder) to path under [outputDirectory]. */
    @get:Input
    abstract val files: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val name = packageName.get()
        val tarballName = name.substringAfterLast('/')
        val url = "${registry.get().trimEnd('/')}/$name/-/$tarballName-${version.get()}.tgz"
        val tarball = URI(url).toURL().openStream().use(InputStream::readBytes)
        verify(tarball, url)

        val wanted = files.get()
        val found = mutableMapOf<String, ByteArray>()
        GZIPInputStream(tarball.inputStream()).use { tar -> readTar(tar) { path, bytes -> if (path in wanted) found[path] = bytes } }
        val missing = wanted.keys - found.keys
        if (missing.isNotEmpty()) throw GradleException("$name@${version.get()} has no $missing")

        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        wanted.forEach { (from, to) -> output.resolve(to).apply { parentFile.mkdirs() }.writeBytes(found.getValue(from)) }
    }

    private fun verify(tarball: ByteArray, url: String) {
        val expected = integrity.get()
        if (!expected.startsWith(SHA512_PREFIX)) throw GradleException("Only sha512 integrity is supported, got $expected")
        val actual = SHA512_PREFIX + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tarball))
        if (actual != expected) throw GradleException("$url doesn't match its pinned integrity: expected $expected, got $actual")
    }

    /** Plain ustar reading: a 512-byte header, then the file padded to 512 bytes. Paths drop the "package/" folder. */
    private fun readTar(input: InputStream, onFile: (String, ByteArray) -> Unit) {
        val header = ByteArray(BLOCK)
        while (input.readNBytes(header, 0, BLOCK) == BLOCK && header.any { it != 0.toByte() }) {
            val name = field(header, NAME_OFFSET, NAME_LENGTH)
            val prefix = field(header, PREFIX_OFFSET, PREFIX_LENGTH)
            val size = field(header, SIZE_OFFSET, SIZE_LENGTH).trim().ifEmpty { "0" }.toLong(OCTAL)
            val type = header[TYPE_OFFSET].toInt().toChar()
            val bytes = input.readNBytes(size.toInt())
            input.skipNBytes((BLOCK - size % BLOCK) % BLOCK)
            if (type == REGULAR_FILE || type == OLD_REGULAR_FILE) {
                val path = (if (prefix.isEmpty()) name else "$prefix/$name").substringAfter('/')
                onFile(path, bytes)
            }
        }
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.US_ASCII).substringBefore('\u0000')

    private companion object {
        const val SHA512_PREFIX = "sha512-"
        const val BLOCK = 512
        const val NAME_OFFSET = 0
        const val NAME_LENGTH = 100
        const val SIZE_OFFSET = 124
        const val SIZE_LENGTH = 12
        const val TYPE_OFFSET = 156
        const val PREFIX_OFFSET = 345
        const val PREFIX_LENGTH = 155
        const val OCTAL = 8
        const val REGULAR_FILE = '0'
        const val OLD_REGULAR_FILE = '\u0000'
    }
}
