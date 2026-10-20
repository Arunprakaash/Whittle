package app.whittle.artifacts

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewClientCompat

/**
 * Runs an artifact. It's untrusted code: JavaScript and its own storage, but no file or content access and no
 * bridge into the app. Links that leave the app's origin open in the browser. [reload] changing reloads it,
 * so edits the agent makes show up while it's open.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArtifactWebView(ref: ArtifactRef, artifacts: Artifacts, reload: Int, modifier: Modifier = Modifier) {
    val loader = remember(ref) { artifacts.loader(ref) }
    val host = remember(ref) { artifacts.host(ref) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedRevision by remember { mutableIntStateOf(reload) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader?.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.host == host) return false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                loadUrl(artifacts.entryUrl(ref))
                webView = this
            }
        },
        onRelease = { it.destroy() },
    )
    LaunchedEffect(reload) {
        if (reload != loadedRevision) {
            loadedRevision = reload
            webView?.reload()
        }
    }
}
