package app.whittle.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A published Whittle release: its [version] without the tag's "v", and the https page with its notes and the APK. */
@Serializable
data class Release(val version: String, val pageUrl: String)

/**
 * The release GitHub's "latest release" endpoint describes in [json], or null for anything else: a draft, a pre-release,
 * a tag that isn't a version, a page that isn't https, or a reply that doesn't parse.
 */
fun parseLatestRelease(json: String): Release? {
    val wire = try {
        lenient.decodeFromString(WireRelease.serializer(), json)
    } catch (exception: SerializationException) {
        return null
    } catch (exception: IllegalArgumentException) {
        return null
    }
    if (wire.draft || wire.prerelease) return null
    val version = wire.tagName.removePrefix("v")
    if (versionParts(version) == null) return null
    if (!wire.pageUrl.startsWith("https://")) return null
    return Release(version, wire.pageUrl)
}

/** Whether [candidate] is a later version than [current], comparing their numbers part by part ("0.20.1" > "0.20.0"). */
fun isNewer(candidate: String, current: String): Boolean {
    val a = versionParts(candidate) ?: return false
    val b = versionParts(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (difference != 0) return difference > 0
    }
    return false
}

/** The numbers of a version like "0.20.0", ignoring a suffix like "-debug"; null when it doesn't start with one. */
private fun versionParts(version: String): List<Int>? =
    Regex("^(\\d+(?:\\.\\d+)*)").find(version)?.groupValues?.get(1)?.split('.')?.map { it.toIntOrNull() ?: return null }

private val lenient = Json { ignoreUnknownKeys = true }

@Serializable
private data class WireRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val pageUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)
