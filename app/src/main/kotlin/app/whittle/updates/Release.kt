package app.whittle.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A published Whittle release: its [version] without the tag's "v", the https page with its notes, and its [apk] when the
 * release has one Whittle can install itself.
 */
@Serializable
data class Release(val version: String, val pageUrl: String, val apk: ReleaseApk? = null)

/** The release's APK: where to download it, the SHA-256 GitHub reports for it (hex), and its size in bytes. */
@Serializable
data class ReleaseApk(val url: String, val sha256: String, val sizeBytes: Long)

/** Which asset of a release is Whittle's APK: its file name, with `{version}` in it, under [urlPrefix] (this repository's). */
data class ApkAsset(val fileName: String, val urlPrefix: String)

/**
 * The release GitHub's "latest release" endpoint describes in [json], or null for anything else: a draft, a pre-release,
 * a tag that isn't a version, a page that isn't https, or a reply that doesn't parse. Its APK is the asset [apkAsset]
 * names, kept only with a SHA-256 digest and a download address under [ApkAsset.urlPrefix].
 */
fun parseLatestRelease(json: String, apkAsset: ApkAsset? = null): Release? {
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
    return Release(version, wire.pageUrl, apkAsset?.let { apkOf(wire.assets, it, version) })
}

private fun apkOf(assets: List<WireAsset>, rule: ApkAsset, version: String): ReleaseApk? {
    val name = rule.fileName.replace(VERSION, version)
    val asset = assets.singleOrNull { it.name == name } ?: return null
    if (!rule.urlPrefix.startsWith("https://") || !asset.url.startsWith(rule.urlPrefix)) return null
    val sha256 = asset.digest?.removePrefix(SHA256_PREFIX)?.takeIf { asset.digest.startsWith(SHA256_PREFIX) && HEX_64.matches(it) } ?: return null
    return ReleaseApk(asset.url, sha256.lowercase(), asset.size)
}

private const val VERSION = "{version}"
private const val SHA256_PREFIX = "sha256:"
private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")

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
    val assets: List<WireAsset> = emptyList(),
)

@Serializable
private data class WireAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
    /** "sha256:<hex>", which GitHub computes when the asset is uploaded. */
    val digest: String? = null,
)
