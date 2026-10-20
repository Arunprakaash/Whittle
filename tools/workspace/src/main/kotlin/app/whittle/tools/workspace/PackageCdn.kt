package app.whittle.tools.workspace

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Maps npm imports to the package CDN, and decides which module addresses a build may download. */
class PackageCdn(private val config: PackageCdnConfig) {
    /** Package versions the workspace's manifest asks for. Entries that aren't registry versions (files, git, workspaces) are skipped. */
    fun versions(manifest: String?): Map<String, String> {
        if (manifest.isNullOrBlank()) return emptyMap()
        val root = try {
            Json.parseToJsonElement(manifest) as? JsonObject
        } catch (exception: SerializationException) {
            null
        } ?: return emptyMap()
        val versions = linkedMapOf<String, String>()
        config.manifestFields.forEach { field ->
            (root[field] as? JsonObject)?.forEach { (name, value) ->
                val version = (value as? JsonPrimitive)?.contentOrNull?.trim()
                if (PACKAGE_NAME.matches(name) && version != null && REGISTRY_VERSION.matches(version)) versions.putIfAbsent(name, version)
            }
        }
        return versions
    }

    /** The CDN address for a bare import such as "react", "react-dom/client" or "@scope/pkg/sub". */
    fun url(specifier: String, versions: Map<String, String>): Outcome<String> {
        val match = SPECIFIER.matchEntire(specifier.trim())
            ?: return Outcome.Failure(HarnessError.NotFound("\"$specifier\" isn't an npm package name"))
        val name = match.groupValues[1]
        val subpath = match.groupValues[2]
        if (!PACKAGE_NAME.matches(name)) return Outcome.Failure(HarnessError.NotFound("\"$specifier\" isn't an npm package name"))
        val version = versions[name]?.let { VERSION_SEPARATOR + encode(it) }.orEmpty()
        val query = config.query.trim().takeIf { it.isNotEmpty() }?.let { QUERY_SEPARATOR + it }.orEmpty()
        return Outcome.Success(config.baseUrl.trimEnd('/') + "/" + name + version + subpath + query)
    }

    /**
     * Only https, only the configured hosts, no user info or port. Read by hand rather than with java.net.URI,
     * which rejects characters browsers allow in paths (a "^" in "react@^19", say).
     */
    fun allows(url: String): Boolean {
        val authority = ORIGIN.find(url.trim())?.groupValues?.get(1) ?: return false
        if ('@' in authority || ':' in authority) return false
        return authority.lowercase() in config.allowedHosts.map { it.lowercase() }
    }

    /** Version ranges may hold spaces and comparison signs; everything outside a plain version is percent-encoded. */
    private fun encode(version: String): String = buildString {
        version.toByteArray(Charsets.UTF_8).forEach { byte ->
            val char = byte.toInt().toChar()
            if (byte >= 0 && (char.isLetterOrDigit() || char in VERSION_SAFE)) append(char) else append("%%%02X".format(byte.toInt() and BYTE_MASK))
        }
    }

    private companion object {
        const val VERSION_SEPARATOR = "@"
        const val QUERY_SEPARATOR = "?"
        const val VERSION_SAFE = ".-_~"
        const val BYTE_MASK = 0xFF

        /** An npm name, optionally scoped, then any subpath: "@scope/name/sub/path". */
        val SPECIFIER = Regex("""^((?:@[^/\s]+/)?[^/\s@:][^/\s:]*)((?:/[^\s?#:]*)?)$""")
        val ORIGIN = Regex("""^https://([^/?#]*)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
        val PACKAGE_NAME = Regex("""^(?:@[a-z0-9~-][a-z0-9._~-]*/)?[a-z0-9~-][a-z0-9._~-]*$""")

        /** A version, tag or range; anything with ":" or "/" is a file, git or workspace reference. */
        val REGISTRY_VERSION = Regex("""^[0-9A-Za-z.^~<>=|*+\s-]+$""")
    }
}
