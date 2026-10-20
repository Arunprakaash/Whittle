package app.whittle.webbuild

import app.whittle.core.common.Outcome
import app.whittle.tools.workspace.BundleJob
import app.whittle.tools.workspace.PackageCdn
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** What the build page gets back for one request. */
class RouteResponse(val status: Int, val mimeType: String, val body: InputStream) {
    companion object {
        fun text(status: Int, message: String): RouteResponse =
            RouteResponse(status, TEXT, ByteArrayInputStream(message.toByteArray()))

        const val TEXT = "text/plain"
    }
}

/**
 * Answers every request the hidden build page makes on its private origin: its own files from the app's
 * assets, the files of the build in progress (only those the job lists), CDN addresses for packages, and CDN
 * modules through [remote]. Each build gets its own id in the path, so a finished build's files can't be read.
 */
class BuildRoutes(
    private val assets: (String) -> InputStream?,
    private val cdn: PackageCdn,
    private val remote: RemoteModules,
) {
    private val jobs = ConcurrentHashMap<String, BundleJob>()

    /** URL prefixes on the page's origin for one build; the page appends a path or an encoded value. */
    fun open(id: String, job: BundleJob): RouteUrls {
        jobs[id] = job
        return RouteUrls(source = "/$SOURCE/$id/", resolve = "/$RESOLVE/$id?$SPECIFIER=", remote = "/$REMOTE/$id?$URL=")
    }

    fun close(id: String) {
        jobs.remove(id)
    }

    /** The page's own address, under [ASSETS]. */
    fun pagePath(page: String): String = "/$ASSETS/$page"

    /** [rawPath] and [rawQuery] are still percent-encoded. */
    fun respond(rawPath: String, rawQuery: String?): RouteResponse {
        val segments = rawPath.trimStart('/').split('/')
        val route = segments.first()
        if (route == ASSETS) return asset(segments.drop(1).map(::decode))
        val job = segments.getOrNull(1)?.let(jobs::get) ?: return RouteResponse.text(NOT_FOUND, "No build is running")
        return when (route) {
            SOURCE -> source(job, segments.drop(2).joinToString("/") { decode(it) })
            RESOLVE -> resolve(job, query(rawQuery, SPECIFIER))
            REMOTE -> remote(query(rawQuery, URL))
            else -> RouteResponse.text(NOT_FOUND, "Unknown route $route")
        }
    }

    private fun asset(segments: List<String>): RouteResponse {
        if (segments.isEmpty() || segments.any { it.isEmpty() || it == "." || it == ".." }) return RouteResponse.text(NOT_FOUND, "Not found")
        val path = segments.joinToString("/")
        val stream = assets(path) ?: return RouteResponse.text(NOT_FOUND, "Not found: $path")
        return RouteResponse(OK, mimeType(path), stream)
    }

    private fun source(job: BundleJob, path: String): RouteResponse {
        if (path !in job.files) return RouteResponse.text(NOT_FOUND, "\"$path\" isn't in the workspace")
        return try {
            RouteResponse(OK, mimeType(path), FileInputStream(File(job.root, path)))
        } catch (exception: IOException) {
            RouteResponse.text(NOT_FOUND, "Can't read \"$path\"")
        }
    }

    private fun resolve(job: BundleJob, specifier: String?): RouteResponse {
        if (specifier.isNullOrBlank()) return RouteResponse.text(BAD_REQUEST, "No package named")
        return when (val url = cdn.url(specifier, job.packageVersions)) {
            is Outcome.Success -> RouteResponse.text(OK, url.value)
            is Outcome.Failure -> RouteResponse.text(NOT_FOUND, url.error.message)
        }
    }

    private fun remote(url: String?): RouteResponse {
        if (url.isNullOrBlank()) return RouteResponse.text(BAD_REQUEST, "No address given")
        if (!cdn.allows(url)) return RouteResponse.text(FORBIDDEN, "Imports by address must use https and one of the allowed package CDNs: $url")
        return remote.fetch(url)
    }

    private fun query(rawQuery: String?, name: String): String? = rawQuery?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.let(::decode)

    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())

    private fun mimeType(path: String): String = MIME_TYPES[path.substringAfterLast('.', "").lowercase()] ?: BINARY

    companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        private const val ASSETS = "app"
        private const val SOURCE = "source"
        private const val RESOLVE = "resolve"
        private const val REMOTE = "remote"
        private const val SPECIFIER = "specifier"
        private const val URL = "url"
        private const val BINARY = "application/octet-stream"
        private val MIME_TYPES = mapOf(
            "html" to "text/html",
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "wasm" to "application/wasm",
            "json" to "application/json",
            "css" to "text/css",
        )
    }
}
