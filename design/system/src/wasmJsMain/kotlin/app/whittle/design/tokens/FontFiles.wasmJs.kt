package app.whittle.design.tokens

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.fetch.Response

/** The font files fetched by [loadWhittleFonts], by name. */
private val fetched = mutableMapOf<String, ByteArray>()

internal actual fun fontFile(name: String): ByteArray? = fetched[name]

/**
 * A browser reads files only asynchronously, so the page fetches the fonts, served beside it, before it draws anything;
 * call this first. A font that can't be fetched leaves the system's in its place.
 */
suspend fun loadWhittleFonts() {
    listOf(WhittleFonts.SANS, WhittleFonts.MONO).forEach { name ->
        val response = window.fetch(name).await<Response>()
        if (!response.ok) return@forEach
        val buffer = Int8Array(response.arrayBuffer().await<ArrayBuffer>())
        fetched[name] = ByteArray(buffer.length) { buffer[it] }
    }
}
