package app.whittle.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** How a download ended: the file is there and is the one GitHub published, it couldn't be fetched, or its bytes differ. */
enum class DownloadResult { OK, NETWORK, MISMATCH }

/**
 * Downloads [apk] to [file], hashing it as it arrives, and keeps it only when its SHA-256 is the one GitHub reported, so a
 * changed or cut-off file never reaches the installer. [progress] gets the bytes so far and the expected total.
 */
suspend fun downloadApk(http: OkHttpClient, apk: ReleaseApk, file: File, progress: (Long, Long) -> Unit): DownloadResult = withContext(Dispatchers.IO) {
    file.parentFile?.mkdirs()
    val digest = MessageDigest.getInstance(SHA_256)
    val fetched = try {
        http.newCall(Request.Builder().url(apk.url).build()).execute().use { response ->
            val body = response.body?.takeIf { response.isSuccessful } ?: return@use false
            val total = body.contentLength().takeIf { it > 0 } ?: apk.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        progress(done, total)
                    }
                }
            }
            true
        }
    } catch (exception: IOException) {
        false
    }
    val result = when {
        !fetched -> DownloadResult.NETWORK
        hex(digest.digest()) != apk.sha256.lowercase() -> DownloadResult.MISMATCH
        else -> DownloadResult.OK
    }
    if (result != DownloadResult.OK) file.delete()
    result
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private const val SHA_256 = "SHA-256"
private const val BUFFER_BYTES = 64 * 1024
