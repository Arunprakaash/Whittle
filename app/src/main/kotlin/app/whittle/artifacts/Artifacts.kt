package app.whittle.artifacts

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import app.whittle.core.common.getOrNull
import app.whittle.settings.ArtifactDefaults
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/** A web app the agent built: which chat's workspace it lives in and its entry page there. */
@Serializable
data class ArtifactRef(val workspace: String, val path: String, val title: String)

/**
 * Serves artifacts from their chat's workspace on a private https origin that only Whittle's WebView can
 * reach (no port is opened), and bundles them into one file for browsers and share targets.
 */
class Artifacts(
    private val context: Context,
    private val workspaces: Workspaces,
    private val config: ArtifactDefaults,
) {
    private val bundler = ArtifactBundler(config.maxInlineBytes)

    /** One origin per chat, so an app's saved data stays with its chat. */
    fun host(ref: ArtifactRef): String =
        ref.workspace.lowercase().replace(NOT_HOST_SAFE, HOST_SEPARATOR).trim('-').take(MAX_LABEL) + "." + config.domainSuffix

    fun entryUrl(ref: ArtifactRef): String =
        "https://${host(ref)}/" + ref.path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }

    /** Null when the chat's workspace is gone, e.g. the chat was deleted. */
    fun loader(ref: ArtifactRef): WebViewAssetLoader? {
        val root = directory(ref) ?: return null
        return WebViewAssetLoader.Builder()
            .setDomain(host(ref))
            .addPathHandler(ROOT_PATH, WebViewAssetLoader.InternalStoragePathHandler(context, root))
            .build()
    }

    fun exists(ref: ArtifactRef): Boolean = entry(ref)?.isFile == true

    /** Opens the app, bundled into one file, in a browser. False when nothing on the phone can open it. */
    suspend fun openInBrowser(ref: ArtifactRef): Boolean {
        val uri = bundled(ref) ?: return false
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, HTML_TYPE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launch(Intent.createChooser(view, ref.title))
    }

    /** Shares the app as one self-contained HTML file. */
    suspend fun share(ref: ArtifactRef): Boolean {
        val uri = bundled(ref) ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType(HTML_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, ref.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(ref.title, uri)
        return launch(Intent.createChooser(send, ref.title))
    }

    private suspend fun bundled(ref: ArtifactRef): Uri? = withContext(Dispatchers.IO) {
        val root = directory(ref) ?: return@withContext null
        val entry = entry(ref)?.takeIf { it.isFile } ?: return@withContext null
        try {
            val folder = File(context.cacheDir, config.sharedDirectoryName).apply { mkdirs() }
            val file = File(folder, fileName(ref.title))
            file.writeText(bundler.bundle(root, entry))
            FileProvider.getUriForFile(context, context.packageName + PROVIDER_SUFFIX, file)
        } catch (exception: IOException) {
            null
        }
    }

    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (exception: ActivityNotFoundException) {
        false
    }

    private fun directory(ref: ArtifactRef): File? = workspaces.directory(WorkspaceId(ref.workspace)).getOrNull()

    private fun entry(ref: ArtifactRef): File? = workspaces.resolve(WorkspaceId(ref.workspace), ref.path).getOrNull()

    private fun fileName(title: String): String =
        title.lowercase().replace(NOT_FILE_SAFE, HOST_SEPARATOR).trim('-').ifEmpty { FALLBACK_NAME }.take(MAX_LABEL) + HTML_EXTENSION

    private companion object {
        const val ROOT_PATH = "/"
        const val HTML_TYPE = "text/html"
        const val HTML_EXTENSION = ".html"
        const val PROVIDER_SUFFIX = ".files"
        const val HOST_SEPARATOR = "-"
        const val FALLBACK_NAME = "app"
        const val MAX_LABEL = 63
        val NOT_HOST_SAFE = Regex("[^a-z0-9-]")
        val NOT_FILE_SAFE = Regex("[^a-z0-9]+")
    }
}
