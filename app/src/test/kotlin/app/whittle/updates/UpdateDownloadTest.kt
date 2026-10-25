package app.whittle.updates

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloadTest {
    private val server = MockWebServer()
    private val file = File(Files.createTempDirectory("update").toFile(), "Whittle-0.24.0.apk")
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @AfterTest
    fun stop() = server.shutdown()

    private fun apk(sha: String = sha256) = ReleaseApk(server.url("/Whittle-0.24.0.apk").toString(), sha, bytes.size.toLong())

    @Test
    fun `a download that matches GitHub's digest is kept, with progress up to the whole file`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val seen = mutableListOf<Long>()
        assertEquals(DownloadResult.OK, downloadApk(OkHttpClient(), apk(), file) { done, total -> seen += done; assertEquals(bytes.size.toLong(), total) })
        assertContentEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `a file whose bytes differ is deleted and never installed`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertEquals(DownloadResult.MISMATCH, downloadApk(OkHttpClient(), apk(sha = "0".repeat(64)), file) { _, _ -> })
        assertFalse(file.exists())
    }

    @Test
    fun `a failed request leaves nothing behind`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(DownloadResult.NETWORK, downloadApk(OkHttpClient(), apk(), file) { _, _ -> })
        assertFalse(file.exists())
    }
}
