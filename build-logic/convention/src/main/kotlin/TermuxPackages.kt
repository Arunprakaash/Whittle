import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * What goes into the on-phone tools, read from app/termux-runtimes.json: Termux packages (Android builds of
 * Node, Python, git...) and how to lay them out for Whittle.
 */
internal class TermuxSpec(
    val repository: String,
    /**
     * Where else a pinned .deb is found, by its file name, when Termux has dropped it (it keeps only current versions):
     * Whittle's own copies, uploaded by CI from the lock file. Each download is checked against the pinned SHA-256.
     */
    val mirrors: List<String>,
    /** Termux's own install prefix, compiled into its programs; rewritten to Whittle's at build time. */
    val termuxPrefix: String,
    /** Folder in the app's data directory the tools live in; padded so the new prefix is as long as Termux's. */
    val installDirectory: String,
    val packages: List<String>,
    /** Android ABI to Termux architecture, e.g. arm64-v8a to aarch64. */
    val abis: Map<String, String>,
    /** Globs, relative to the prefix, of files the app doesn't need (headers, manuals, test suites). */
    val exclude: List<String>,
    /** Extra links in the prefix, e.g. bin/sh to Android's shell, so scripts' #! lines find an interpreter. */
    val symlinks: Map<String, String>,
) {
    /** The prefix the programs are rewritten to: /data/data/<appId>/<installDirectory>, padded to Termux's length. */
    fun prefixFor(applicationId: String): String {
        val base = "/data/data/$applicationId/$installDirectory"
        if (base.length > termuxPrefix.length) {
            throw GradleException("'$base' is longer than Termux's prefix '$termuxPrefix'; use a shorter installDirectory")
        }
        return base.padEnd(termuxPrefix.length, PAD)
    }

    companion object {
        private const val PAD = '_'

        @Suppress("UNCHECKED_CAST")
        fun read(file: File): TermuxSpec {
            val json = JsonSlurper().parse(file) as Map<String, Any?>
            return TermuxSpec(
                repository = json["repository"] as String,
                mirrors = (json["mirrors"] as List<String>?).orEmpty(),
                termuxPrefix = json["termuxPrefix"] as String,
                installDirectory = json["installDirectory"] as String,
                packages = json["packages"] as List<String>,
                abis = json["abis"] as Map<String, String>,
                exclude = json["exclude"] as List<String>,
                symlinks = json["symlinks"] as Map<String, String>,
            )
        }
    }
}

/** One pinned .deb. */
internal data class LockedPackage(val name: String, val version: String, val filename: String, val sha256: String)

/** Package index entries for one architecture (the repository's Packages file). */
internal class TermuxIndex(private val entries: Map<String, Map<String, String>>) {
    /**
     * [roots] and everything they depend on, each once. For "a | b", a choice that's already a root or already
     * picked wins (npm needs "nodejs | nodejs-lts"; with nodejs-lts as a root, only that one ships).
     */
    fun resolve(roots: List<String>): List<LockedPackage> {
        val ordered = linkedMapOf<String, LockedPackage>()
        fun visit(name: String, from: String?) {
            if (name in ordered) return
            val entry = entries[name] ?: throw GradleException("Termux has no package '$name'${from?.let { " (needed by $it)" }.orEmpty()}")
            ordered[name] = LockedPackage(name, entry.getValue("Version"), entry.getValue("Filename"), entry.getValue("SHA256"))
            dependencies(entry).forEach { choices -> visit(choices.firstOrNull { it in ordered || it in roots } ?: choices.first(), name) }
        }
        roots.forEach { visit(it, null) }
        return ordered.values.toList()
    }

    /** "a (>= 1), b | c" -> [a], [b, c]: each dependency's choices, without versions. */
    private fun dependencies(entry: Map<String, String>): List<List<String>> =
        entry["Depends"].orEmpty().split(',').map { dependency ->
            dependency.split('|').map { it.trim().substringBefore(' ') }.filter(String::isNotEmpty)
        }.filter { it.isNotEmpty() }

    companion object {
        fun parse(text: String): TermuxIndex {
            val entries = mutableMapOf<String, Map<String, String>>()
            text.split("\n\n").forEach { block ->
                val fields = block.lines().filter { it.isNotBlank() && !it.startsWith(" ") }
                    .associate { it.substringBefore(": ") to it.substringAfter(": ", "") }
                fields["Package"]?.let { entries[it] = fields }
            }
            return TermuxIndex(entries)
        }
    }
}

/** A file, link or folder inside a package's data archive. Paths are relative to the archive root, without "./". */
internal sealed interface TarEntry {
    val path: String

    class Regular(override val path: String, val bytes: ByteArray, val executable: Boolean) : TarEntry
    class Symlink(override val path: String, val target: String) : TarEntry
    class Hardlink(override val path: String, val target: String) : TarEntry
    class Directory(override val path: String) : TarEntry
}

internal object Debs {
    fun download(url: String): ByteArray = URI(url).toURL().openStream().use(InputStream::readBytes)

    /**
     * The pinned [locked] .deb: from [cache] when it's there and intact, else from Termux, else from the first of the
     * spec's mirrors that has it. Whatever is downloaded must match the pinned SHA-256, and is kept in [cache].
     */
    fun fetch(spec: TermuxSpec, locked: LockedPackage, cache: File): ByteArray {
        val name = locked.filename.substringAfterLast('/')
        val file = File(cache.apply { mkdirs() }, name)
        val what = "${locked.name} ${locked.version}"
        file.takeIf { it.isFile }?.readBytes()?.takeIf { sha256(it).equals(locked.sha256, ignoreCase = true) }?.let { return it }
        val sources = listOf(spec.repository.trimEnd('/') + "/" + locked.filename) + spec.mirrors.map { it.trimEnd('/') + "/" + name }
        val failures = mutableListOf<String>()
        for (url in sources) {
            val bytes = try {
                download(url)
            } catch (exception: java.io.IOException) {
                failures += "$url: ${exception.javaClass.simpleName}"
                continue
            }
            verify(bytes, locked.sha256, what)
            file.writeBytes(bytes)
            return bytes
        }
        throw GradleException(
            "Neither Termux nor a mirror has $what (${failures.joinToString("; ")}). Run ./gradlew :app:updateTermuxLock to pin " +
                "what Termux has now, then test Linux on a phone before committing the new lock.",
        )
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun verify(bytes: ByteArray, sha256: String, what: String) {
        val actual = sha256(bytes)
        if (!actual.equals(sha256, ignoreCase = true)) throw GradleException("$what doesn't match its pinned SHA-256: expected $sha256, got $actual")
    }

    /** The entries of a .deb's data archive (an ar archive holding data.tar.xz, .gz or plain .tar). */
    fun dataEntries(deb: ByteArray, what: String): List<TarEntry> {
        if (String(deb, 0, AR_MAGIC.length, Charsets.US_ASCII) != AR_MAGIC) throw GradleException("$what isn't a .deb")
        var offset = AR_MAGIC.length
        while (offset + AR_HEADER <= deb.size) {
            val name = String(deb, offset, AR_NAME, Charsets.US_ASCII).trim().trimEnd('/')
            val size = String(deb, offset + AR_SIZE_OFFSET, AR_SIZE, Charsets.US_ASCII).trim().toInt()
            val body = ByteArrayInputStream(deb, offset + AR_HEADER, size)
            if (name.startsWith(DATA_MEMBER)) {
                val tar = when {
                    name.endsWith(".xz") -> XZInputStream(body)
                    name.endsWith(".gz") -> GZIPInputStream(body)
                    name.endsWith(".tar") -> body
                    else -> throw GradleException("$what uses $name, which the build can't read")
                }
                return tar.use(::readTar)
            }
            offset += AR_HEADER + size + size % 2
        }
        throw GradleException("$what has no data archive")
    }

    /** ustar with GNU long names and pax path headers, as dpkg-deb writes them. */
    private fun readTar(input: InputStream): List<TarEntry> {
        val entries = mutableListOf<TarEntry>()
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        while (input.readNBytes(header, 0, BLOCK) == BLOCK && header.any { it != 0.toByte() }) {
            val size = field(header, SIZE_OFFSET, SIZE_LENGTH).trim().ifEmpty { "0" }.toLong(OCTAL)
            val body = input.readNBytes(size.toInt())
            input.skipNBytes((BLOCK - size % BLOCK) % BLOCK)
            val type = header[TYPE_OFFSET].toInt().toChar()
            when (type) {
                GNU_LONG_NAME -> { longName = String(body).trimEnd('\u0000'); continue }
                GNU_LONG_LINK -> { longLink = String(body).trimEnd('\u0000'); continue }
                PAX_HEADER -> {
                    pax(body).let { longName = it["path"] ?: longName; longLink = it["linkpath"] ?: longLink }
                    continue
                }
                PAX_GLOBAL -> continue
            }
            val prefix = field(header, PREFIX_OFFSET, PREFIX_LENGTH)
            val rawName = longName ?: (if (prefix.isEmpty()) "" else "$prefix/") + field(header, NAME_OFFSET, NAME_LENGTH)
            val link = longLink ?: field(header, LINK_OFFSET, LINK_LENGTH)
            longName = null
            longLink = null
            val path = rawName.removePrefix("./").trimEnd('/')
            if (path.isEmpty()) continue
            if (path.startsWith("/") || path.split('/').any { it == ".." }) throw GradleException("Unsafe path in package: $path")
            entries += when (type) {
                REGULAR, OLD_REGULAR -> TarEntry.Regular(path, body, field(header, MODE_OFFSET, MODE_LENGTH).trim().ifEmpty { "0" }.toInt(OCTAL) and ANY_EXECUTE != 0)
                SYMLINK -> TarEntry.Symlink(path, link)
                HARDLINK -> TarEntry.Hardlink(path, link.removePrefix("./"))
                DIRECTORY -> TarEntry.Directory(path)
                else -> continue
            }
        }
        return entries
    }

    /** "<length> key=value\n" records. */
    private fun pax(body: ByteArray): Map<String, String> {
        val records = mutableMapOf<String, String>()
        var offset = 0
        while (offset < body.size) {
            val space = (offset until body.size).first { body[it] == ' '.code.toByte() }
            val length = String(body, offset, space - offset).toInt()
            val record = String(body, space + 1, length - (space - offset) - 2, Charsets.UTF_8)
            records[record.substringBefore('=')] = record.substringAfter('=')
            offset += length
        }
        return records
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.UTF_8).substringBefore('\u0000')

    private const val AR_MAGIC = "!<arch>\n"
    private const val AR_HEADER = 60
    private const val AR_NAME = 16
    private const val AR_SIZE_OFFSET = 48
    private const val AR_SIZE = 10
    private const val DATA_MEMBER = "data.tar"
    private const val BLOCK = 512
    private const val NAME_OFFSET = 0
    private const val NAME_LENGTH = 100
    private const val MODE_OFFSET = 100
    private const val MODE_LENGTH = 8
    private const val ANY_EXECUTE = 0b001_001_001
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val TYPE_OFFSET = 156
    private const val LINK_OFFSET = 157
    private const val LINK_LENGTH = 100
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155
    private const val OCTAL = 8
    private const val REGULAR = '0'
    private const val OLD_REGULAR = '\u0000'
    private const val HARDLINK = '1'
    private const val SYMLINK = '2'
    private const val DIRECTORY = '5'
    private const val GNU_LONG_NAME = 'L'
    private const val GNU_LONG_LINK = 'K'
    private const val PAX_HEADER = 'x'
    private const val PAX_GLOBAL = 'g'
}

internal object Lockfile {
    @Suppress("UNCHECKED_CAST")
    fun read(file: File): Map<String, List<LockedPackage>> {
        if (!file.isFile) throw GradleException("${file.name} is missing; run ./gradlew :app:updateTermuxLock")
        val json = JsonSlurper().parse(file) as Map<String, List<Map<String, String>>>
        return json.mapValues { (_, packages) ->
            packages.map { LockedPackage(it.getValue("name"), it.getValue("version"), it.getValue("filename"), it.getValue("sha256")) }
        }
    }
}
