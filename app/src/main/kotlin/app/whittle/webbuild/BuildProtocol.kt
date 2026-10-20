package app.whittle.webbuild

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The JSON messages between the app and the hidden build page (assets/web-build/host.js, bundle.js).

/** Route prefixes the page fetches workspace files, package addresses and CDN modules from. */
@Serializable
data class RouteUrls(val source: String, val resolve: String, val remote: String)

@Serializable
internal data class BuildMessage(val type: String, val id: String, val request: BuildRequest, val routes: RouteUrls) {
    companion object {
        const val TYPE = "build"
    }
}

@Serializable
internal data class BuildRequest(
    val entryPoints: List<BuildEntry>,
    val files: List<String>,
    val resolveExtensions: List<String>,
    val loaders: Map<String, String>,
    val options: JsonObject,
    val maxMessages: Int,
)

@Serializable
internal data class BuildEntry(@SerialName("in") val path: String, val out: String)

/** From the page: "ready" once esbuild is loaded, "failed" when it can't load, "result" for a build. */
@Serializable
internal data class HostMessage(val type: String, val id: String? = null, val message: String? = null, val result: HostResult? = null) {
    companion object {
        const val READY = "ready"
        const val FAILED = "failed"
        const val RESULT = "result"
    }
}

@Serializable
internal data class HostResult(val ok: Boolean, val files: List<HostFile>, val errors: List<String>, val warnings: List<String>)

@Serializable
internal data class HostFile(val path: String, val base64: String)
