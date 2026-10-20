package app.whittle.webbuild

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.tools.workspace.BundleJob
import app.whittle.tools.workspace.BundleResult
import app.whittle.tools.workspace.BundledFile
import app.whittle.tools.workspace.WebBuildConfig
import app.whittle.tools.workspace.WebBundler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs esbuild's WebAssembly build in a WebView that is never shown. The page and esbuild ship in the app's
 * assets and load from a private https origin that Whittle answers itself ([BuildRoutes]); the page can't
 * reach the network directly, only the package CDN through [routes]. The project's code is only parsed,
 * never run. One build at a time; the WebView starts on the first build and closes after a quiet spell.
 */
class WebViewBundler(
    context: Context,
    private val config: WebBuildConfig,
    private val routes: BuildRoutes,
    private val scope: CoroutineScope,
) : WebBundler {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Touched only on the main thread. */
    private var host: Host? = null
    private var idleShutdown: Job? = null

    override suspend fun bundle(job: BundleJob): Outcome<BundleResult> = lock.withLock {
        idleShutdown?.cancel()
        val id = UUID.randomUUID().toString()
        try {
            withTimeout(config.timeoutSeconds * MILLIS_PER_SECOND) { run(id, job) }
        } catch (exception: TimeoutCancellationException) {
            // A stuck esbuild would hold up every later build, so start fresh next time.
            withContext(Dispatchers.Main) { stopHost() }
            Outcome.Failure(HarnessError.Network("the build took longer than ${config.timeoutSeconds} seconds"))
        } finally {
            routes.close(id)
            scheduleShutdown()
        }
    }

    private suspend fun run(id: String, job: BundleJob): Outcome<BundleResult> {
        val unsafe = job.files.firstOrNull { !isSafe(it) }
        if (unsafe != null) return Outcome.Failure(HarnessError.Configuration("\"$unsafe\" isn't a plain workspace path"))
        val active = withContext(Dispatchers.Main) { host ?: startHost() } ?: return Outcome.Failure(
            HarnessError.Configuration("this phone's WebView is too old to build apps; update Android System WebView"),
        )
        val proxy = try {
            active.ready.await()
        } catch (exception: HostFailure) {
            withContext(Dispatchers.Main) { stopHost() }
            return Outcome.Failure(HarnessError.Configuration("the app builder didn't start: ${exception.message}"))
        }
        val reply = CompletableDeferred<HostResult>()
        active.pending[id] = reply
        try {
            val message = BuildMessage(BuildMessage.TYPE, id, request(job), routes.open(id, job))
            withContext(Dispatchers.Main) { proxy.postMessage(json.encodeToString(BuildMessage.serializer(), message)) }
            return result(reply.await())
        } catch (exception: HostFailure) {
            withContext(Dispatchers.Main) { stopHost() }
            return Outcome.Failure(HarnessError.Configuration("the app builder stopped: ${exception.message}"))
        } finally {
            active.pending.remove(id)
        }
    }

    private fun request(job: BundleJob) = BuildRequest(
        entryPoints = job.entryPoints.map { BuildEntry(it.path, it.outName) },
        files = job.files,
        resolveExtensions = config.resolveExtensions,
        loaders = config.loaders,
        options = config.esbuildOptions,
        maxMessages = config.maxMessages,
    )

    private fun result(result: HostResult): Outcome<BundleResult> {
        val files = result.files.map { file ->
            if (!isSafe(file.path)) return Outcome.Failure(HarnessError.Configuration("the builder wrote outside its folder: ${file.path}"))
            val bytes = try {
                Base64.getDecoder().decode(file.base64)
            } catch (exception: IllegalArgumentException) {
                return Outcome.Failure(HarnessError.Serialization("the builder sent a damaged file: ${file.path}"))
            }
            BundledFile(file.path, bytes)
        }
        return Outcome.Success(BundleResult(result.ok, files, result.errors, result.warnings))
    }

    private fun scheduleShutdown() {
        idleShutdown = scope.launch {
            delay(config.host.idleShutdownSeconds * MILLIS_PER_SECOND)
            lock.withLock { withContext(Dispatchers.Main) { stopHost() } }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startHost(): Host? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return null
        val webView = WebView(appContext)
        val started = Host(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
        }
        WebViewCompat.addWebMessageListener(webView, CHANNEL, setOf("https://${config.host.domain}")) { _, message, _, isMainFrame, replyProxy ->
            if (isMainFrame) started.receive(message, replyProxy)
        }
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = intercept(request.url)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (host?.webView == view) stopHost()
                started.fail("its WebView process ended")
                return true
            }
        }
        webView.loadUrl("https://${config.host.domain}${routes.pagePath(config.host.page)}")
        host = started
        return started
    }

    /** Main thread only. */
    private fun stopHost() {
        val stopping = host ?: return
        host = null
        stopping.fail("it was shut down")
        stopping.webView.destroy()
    }

    /** Called on WebView's IO threads. Anything not on the build origin is refused, so the page has no other way out. */
    private fun intercept(url: Uri): WebResourceResponse {
        val response = if (url.scheme == HTTPS && url.host == config.host.domain) {
            routes.respond(url.encodedPath.orEmpty(), url.encodedQuery)
        } else {
            RouteResponse.text(BuildRoutes.FORBIDDEN, "The build page can't load $url")
        }
        return WebResourceResponse(response.mimeType, null, response.status, reason(response.status), NO_STORE, response.body)
    }

    private fun reason(status: Int): String = if (status in OK_RANGE) "OK" else "Error"

    private fun isSafe(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** One loaded build page: ready once esbuild has started, then one pending reply per build. */
    private inner class Host(val webView: WebView) {
        val ready = CompletableDeferred<JavaScriptReplyProxy>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<HostResult>>()

        fun receive(message: WebMessageCompat, replyProxy: JavaScriptReplyProxy) {
            val parsed = try {
                json.decodeFromString(HostMessage.serializer(), message.data ?: return)
            } catch (exception: SerializationException) {
                return
            } catch (exception: IllegalArgumentException) {
                return
            }
            when (parsed.type) {
                HostMessage.READY -> ready.complete(replyProxy)
                HostMessage.FAILED -> fail(parsed.message ?: "esbuild didn't load")
                HostMessage.RESULT -> parsed.result?.let { result -> parsed.id?.let(pending::get)?.complete(result) }
            }
        }

        fun fail(reason: String) {
            val failure = HostFailure(reason)
            ready.completeExceptionally(failure)
            pending.values.forEach { it.completeExceptionally(failure) }
        }
    }

    private class HostFailure(message: String) : IOException(message)

    private companion object {
        /** The object the page talks through; host.js reads window.whittleBuild. */
        const val CHANNEL = "whittleBuild"
        const val HTTPS = "https"
        const val MILLIS_PER_SECOND = 1_000L
        val OK_RANGE = 200..299
        val NO_STORE = mapOf("Cache-Control" to "no-store")
    }
}
