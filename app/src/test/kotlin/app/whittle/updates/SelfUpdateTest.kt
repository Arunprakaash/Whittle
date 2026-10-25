package app.whittle.updates

import app.whittle.apps.InstallResult
import app.whittle.settings.UpdateDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfUpdateTest {
    private val server = MockWebServer()
    private val root = Files.createTempDirectory("self-update").toFile()
    private val downloads = File(root, "updates").apply { mkdirs() }

    @AfterTest
    fun stop() = server.shutdown()

    private val http = OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }
        .build()

    private val check = UpdateCheck(
        http,
        UpdateDefaults(check = true, latestReleaseUrl = "https://api.github.com/repos/x/releases/latest", everyHours = 24, fileName = "update-check.json", apkFileName = "Whittle-{version}.apk", apkUrlPrefix = "https://github.com/x/releases/download/", downloadDirectory = "updates"),
        currentVersion = "0.20.0",
        file = File(root, "update-check.json"),
    ) { 0L }

    private fun release(withApk: Boolean): MockResponse {
        val assets = if (withApk) {
            """[{"name":"Whittle-0.21.0.apk","browser_download_url":"https://github.com/x/releases/download/v0.21.0/Whittle-0.21.0.apk","size":4,"digest":"sha256:${"a".repeat(64)}"}]"""
        } else {
            "[]"
        }
        return MockResponse().setBody("""{"tag_name":"v0.21.0","html_url":"https://github.com/x/releases/tag/v0.21.0","assets":$assets}""")
    }

    /** The phone's side, recorded: what was installed and what the notification showed. */
    private class Host(var allowed: Boolean = true, val result: InstallResult = InstallResult.INSTALLED) : SelfUpdateHost {
        val installed = mutableListOf<File>()
        val shown = mutableListOf<Int?>()
        var done = 0

        override fun canInstall() = allowed
        override suspend fun install(apk: File): InstallResult {
            installed += apk
            assertTrue(apk.exists())
            return result
        }
        override fun progress(version: String, percent: Int?) { shown += percent }
        override fun done() { done++ }
    }

    private fun TestScope.selfUpdate(host: Host, result: DownloadResult = DownloadResult.OK, downloaded: MutableList<File> = mutableListOf()) =
        SelfUpdate(
            check,
            download = { _, file, progress ->
                downloaded += file
                file.writeText("apk!")
                progress(1, 4)
                progress(2, 4)
                progress(4, 4)
                if (result != DownloadResult.OK) file.delete()
                result
            },
            directory = downloads,
            host = host,
            scope = this,
        )

    @Test
    fun `downloads, installs and cleans up, with whole-percent progress`() = runTest {
        server.enqueue(release(withApk = true))
        check.checkIfDue()
        val host = Host()
        val update = selfUpdate(host)
        update.start()
        advanceUntilIdle()
        assertEquals(listOf(0, 25, 50, 100, null), host.shown)
        assertEquals("Whittle-0.21.0.apk", host.installed.single().name)
        assertFalse(host.installed.single().exists())
        assertEquals(1, host.done)
        assertEquals(UpdateState.Idle, update.state.value)
    }

    @Test
    fun `a download that doesn't match GitHub's digest is never installed`() = runTest {
        server.enqueue(release(withApk = true))
        check.checkIfDue()
        val host = Host()
        val update = selfUpdate(host, DownloadResult.MISMATCH)
        update.start()
        advanceUntilIdle()
        assertTrue(host.installed.isEmpty())
        assertEquals(UpdateState.Failed("0.21.0", UpdateFailure.MISMATCH), update.state.value)
        assertEquals(1, host.done)
    }

    @Test
    fun `without install permission it asks first and downloads nothing`() = runTest {
        server.enqueue(release(withApk = true))
        check.checkIfDue()
        val host = Host(allowed = false)
        val downloaded = mutableListOf<File>()
        val update = selfUpdate(host, downloaded = downloaded)
        update.start()
        advanceUntilIdle()
        assertEquals(UpdateState.NeedsPermission("0.21.0"), update.state.value)
        assertTrue(downloaded.isEmpty())
        // Allowed in Android's settings, the same Update carries on.
        host.allowed = true
        update.start()
        advanceUntilIdle()
        assertEquals(1, host.installed.size)
        assertEquals(UpdateState.Idle, update.state.value)
    }

    @Test
    fun `an APK Android says isn't this Whittle fails as not Whittle`() = runTest {
        server.enqueue(release(withApk = true))
        check.checkIfDue()
        val update = selfUpdate(Host(result = InstallResult.KEY_CHANGED))
        update.start()
        advanceUntilIdle()
        assertEquals(UpdateState.Failed("0.21.0", UpdateFailure.NOT_WHITTLE), update.state.value)
    }

    @Test
    fun `a release saved without an APK asks GitHub again, and says so when there still is none`() = runTest {
        server.enqueue(release(withApk = false))
        server.enqueue(release(withApk = false))
        check.checkIfDue()
        val update = selfUpdate(Host())
        update.start()
        val failed = update.state.first { it is UpdateState.Failed }
        assertEquals(UpdateState.Failed("0.21.0", UpdateFailure.NO_APK), failed)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `leftover downloads are deleted`() = runTest {
        File(downloads, "Whittle-0.19.0.apk").writeText("old")
        selfUpdate(Host()).cleanUp()
        assertTrue(downloads.listFiles().isNullOrEmpty())
    }
}
