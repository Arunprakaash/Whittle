package app.whittle.webbuild

import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Downloads package modules for builds. [client] carries a disk cache, so packages download once; when the
 * phone is offline, a cached copy is used even if it's stale, so rebuilding an app keeps working.
 */
class RemoteModules(
    private val client: OkHttpClient,
    private val maxBytes: Long,
    /** Checked again after redirects, so a CDN can't send the build somewhere else. */
    private val allows: (String) -> Boolean,
) {
    fun fetch(url: String): RouteResponse {
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (exception: IllegalArgumentException) {
            return RouteResponse.text(BuildRoutes.BAD_REQUEST, "\"$url\" isn't a web address")
        }
        val response = try {
            client.newCall(request).execute()
        } catch (exception: IOException) {
            try {
                client.newCall(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()).execute()
            } catch (cacheMiss: IOException) {
                return RouteResponse.text(BAD_GATEWAY, "Couldn't download $url; is the phone online?")
            }
        }
        return response.use(::read)
    }

    private fun read(response: Response): RouteResponse {
        val url = response.request.url.toString()
        if (!allows(url)) return RouteResponse.text(BuildRoutes.FORBIDDEN, "$url redirected to an address builds may not use")
        when {
            response.code == GATEWAY_TIMEOUT && response.networkResponse == null ->
                return RouteResponse.text(BAD_GATEWAY, "Couldn't download $url; is the phone online?")
            response.code == BuildRoutes.NOT_FOUND -> return RouteResponse.text(BuildRoutes.NOT_FOUND, "$url wasn't found; check the package name and version")
            !response.isSuccessful -> return RouteResponse.text(BAD_GATEWAY, "$url answered ${response.code}")
        }
        val body = response.body ?: return RouteResponse.text(BAD_GATEWAY, "$url sent nothing")
        if (body.contentLength() > maxBytes) return tooLarge(url)
        val bytes = try {
            body.byteStream().use { it.readNBytesCompat(maxBytes + 1) }
        } catch (exception: IOException) {
            return RouteResponse.text(BAD_GATEWAY, "Download of $url was cut off")
        }
        if (bytes.size > maxBytes) return tooLarge(url)
        val type = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: JAVASCRIPT
        return RouteResponse(BuildRoutes.OK, type, ByteArrayInputStream(bytes))
    }

    private fun tooLarge(url: String) = RouteResponse.text(BuildRoutes.FORBIDDEN, "$url is larger than ${maxBytes / BYTES_PER_MEGABYTE} MB")

    /** InputStream.readNBytes(Int) needs API 33. */
    private fun InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(BUFFER.toLong(), limit - out.size()).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val BAD_GATEWAY = 502
        const val GATEWAY_TIMEOUT = 504
        const val JAVASCRIPT = "text/javascript"
        const val BUFFER = 64 * 1024
        const val BYTES_PER_MEGABYTE = 1024 * 1024
    }
}
