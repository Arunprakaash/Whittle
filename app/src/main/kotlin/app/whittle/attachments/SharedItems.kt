package app.whittle.attachments

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What another app shared with Whittle through Android's Share menu: files, and any words that came with them. */
data class SharedItems(val uris: List<Uri>, val text: String?) {
    companion object {
        /** Null for an intent that isn't a share, or a share with nothing in it. */
        fun from(intent: Intent?): SharedItems? {
            val uris = when (intent?.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> return null
            }
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
            return SharedItems(uris, text).takeIf { uris.isNotEmpty() || text != null }
        }
    }
}

/** Where the camera app saves a photo before it's added: a fresh file, after clearing out the last ones. */
object CameraFiles {
    fun create(context: Context, directoryName: String): Uri {
        val directory = File(context.cacheDir, directoryName)
        directory.listFiles()?.forEach { it.delete() }
        directory.mkdirs()
        val file = File(directory, SimpleDateFormat(NAME_PATTERN, Locale.ROOT).format(Date()))
        return CacheFiles.uriFor(context, file)
    }

    private const val NAME_PATTERN = "'IMG_'yyyyMMdd_HHmmss'.jpg'"
}

/** Hands a file in the app's cache to another app by a content address, the only files the provider serves. */
object CacheFiles {
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)

    /** Matches the provider's authority in the manifest. */
    private const val AUTHORITY_SUFFIX = ".attachments"
}
