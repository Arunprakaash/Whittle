import org.gradle.api.GradleException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DebsFetchTest {
    private val cache = Files.createTempDirectory("debs-cache").toFile().resolve("cache")
    private val good = "the pinned bytes".toByteArray()
    private val locked = LockedPackage("proot", "5.1", "pool/main/p/proot/proot_5.1_aarch64.deb", Debs.sha256(good))
    private val spec = TermuxSpec(
        repository = "https://termux.example/apt/",
        mirrors = listOf("https://mirror.example/one/", "https://mirror.example/two"),
        connectTimeoutSeconds = 1,
        readTimeoutSeconds = 1,
        termuxPrefix = "/prefix",
        installDirectory = "rt",
        packages = emptyList(),
        abis = emptyMap(),
        exclude = emptyList(),
        symlinks = emptyMap(),
    )
    private val termux = "https://termux.example/apt/pool/main/p/proot/proot_5.1_aarch64.deb"
    private val mirrorOne = "https://mirror.example/one/proot_5.1_aarch64.deb"
    private val mirrorTwo = "https://mirror.example/two/proot_5.1_aarch64.deb"

    /** A download that answers from [sources] (a URL missing from it is an IOException) and records what it was asked for. */
    private class Net(private val sources: Map<String, ByteArray>) {
        val asked = mutableListOf<String>()
        fun get(url: String): ByteArray {
            asked += url
            return sources[url] ?: throw IOException("404 $url")
        }
    }

    @Test
    fun `an intact cached file is used without any download`() {
        cache.mkdirs()
        File(cache, "proot_5.1_aarch64.deb").writeBytes(good)
        val net = Net(emptyMap())

        assertContentEquals(good, Debs.fetch(spec, locked, cache, net::get))
        assertTrue(net.asked.isEmpty())
    }

    @Test
    fun `a damaged cached file is downloaded again and replaced`() {
        cache.mkdirs()
        val file = File(cache, "proot_5.1_aarch64.deb").apply { writeBytes("damaged".toByteArray()) }
        val net = Net(mapOf(termux to good))

        assertContentEquals(good, Debs.fetch(spec, locked, cache, net::get))
        assertEquals(listOf(termux), net.asked)
        assertContentEquals(good, file.readBytes())
    }

    @Test
    fun `Termux first, and what it serves is kept in the cache`() {
        val net = Net(mapOf(termux to good, mirrorOne to good))

        assertContentEquals(good, Debs.fetch(spec, locked, cache, net::get))
        assertEquals(listOf(termux), net.asked)
        assertContentEquals(good, File(cache, "proot_5.1_aarch64.deb").readBytes())
    }

    @Test
    fun `Termux without the file falls back to the mirror under the file name`() {
        val net = Net(mapOf(mirrorOne to good))

        assertContentEquals(good, Debs.fetch(spec, locked, cache, net::get))
        assertEquals(listOf(termux, mirrorOne), net.asked)
    }

    @Test
    fun `bytes that don't match the pin from one source move on to the next`() {
        val net = Net(mapOf(termux to "<html>captive portal</html>".toByteArray(), mirrorOne to good))

        assertContentEquals(good, Debs.fetch(spec, locked, cache, net::get))
        assertEquals(listOf(termux, mirrorOne), net.asked)
        assertContentEquals(good, File(cache, "proot_5.1_aarch64.deb").readBytes())
    }

    @Test
    fun `a bad mirror does not stop a later one`() {
        val net = Net(mapOf(mirrorOne to "wrong".toByteArray(), mirrorTwo to good))

        assertContentEquals(good, Debs.fetch(spec, locked, cache, net::get))
        assertEquals(listOf(termux, mirrorOne, mirrorTwo), net.asked)
    }

    @Test
    fun `every source failing is one error that names them all, and caches nothing`() {
        val net = Net(mapOf(mirrorOne to "wrong".toByteArray()))

        val error = assertFailsWith<GradleException> { Debs.fetch(spec, locked, cache, net::get) }

        assertEquals(listOf(termux, mirrorOne, mirrorTwo), net.asked)
        listOf(termux, mirrorOne, mirrorTwo).forEach { assertTrue(it in error.message.orEmpty(), "the error names $it") }
        assertTrue("SHA-256" in error.message.orEmpty())
        assertTrue(!File(cache, "proot_5.1_aarch64.deb").exists())
    }
}
