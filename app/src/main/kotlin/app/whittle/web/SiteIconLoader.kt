package app.whittle.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.whittle.design.components.SiteIconSource
import app.whittle.settings.SiteIconDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches site icons straight from each site (never through a third-party icon service, which would learn
 * what the user reads), scales them to [SiteIconDefaults.sizePx] and keeps them on disk. A host that has no
 * usable icon isn't asked again until the app restarts.
 */
class SiteIconLoader(
    private val http: OkHttpClient,
    private val directory: File,
    private val config: SiteIconDefaults,
) : SiteIconSource {
    private val memory = LruCache<String, ImageBitmap>(config.memoryEntries)
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    /** Fetches outlive the row that asked, so a scrolled-away row doesn't waste a half-done download. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun load(host: String): ImageBitmap? {
        if (!config.enabled) return null
        val home = publicHomePage(host) ?: return null
        val key = home.host
        memory.get(key)?.let { return it }
        if (key in missing) return null
        val pending = inFlight.computeIfAbsent(key) {
            scope.async { (cached(key) ?: fetch(home, key)).also { inFlight.remove(key) } }
        }
        return pending.await()
    }

    private fun cached(key: String): ImageBitmap? =
        file(key).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap()?.also { memory.put(key, it) }

    private fun fetch(home: HttpUrl, key: String): ImageBitmap? {
        val page = download(home, config.maxPageBytes)
        val candidates = page?.let { (url, bytes) -> SiteIconLinks.candidates(bytes.decodeToString(), url, config.sizePx) }
            ?: listOf(SiteIconLinks.fallback(home))
        val bitmap = candidates.firstNotNullOfOrNull { url -> download(url, config.maxIconBytes)?.let { decode(it.second) } }
        if (bitmap == null) {
            missing += key
            return null
        }
        save(bitmap, key)
        return bitmap.asImageBitmap().also { memory.put(key, it) }
    }

    /** The final url (after redirects) and up to [limit] bytes of the body, or null when the request fails. */
    private fun download(url: HttpUrl, limit: Int): Pair<HttpUrl, ByteArray>? = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) return null
            val source = body.source()
            source.request(limit.toLong())
            response.request.url to source.buffer.readByteArray(minOf(source.buffer.size, limit.toLong()))
        }
    } catch (exception: IOException) {
        null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= config.sizePx) return bitmap
        val scale = config.sizePx.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun save(bitmap: Bitmap, key: String) {
        try {
            directory.mkdirs()
            val target = file(key)
            val partial = File(directory, "$key$PARTIAL_SUFFIX")
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            partial.renameTo(target)
        } catch (exception: IOException) {
            // The icon still shows from memory; it is fetched again next launch.
        }
    }

    private fun file(key: String) = File(directory, "$key$FILE_SUFFIX")

    private companion object {
        const val FILE_SUFFIX = ".png"
        const val PARTIAL_SUFFIX = ".part"
        const val PNG_QUALITY = 100
    }
}
