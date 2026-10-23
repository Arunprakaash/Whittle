package app.whittle.phone

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import app.whittle.attachments.CacheFiles
import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.tools.android.ToolJson
import app.whittle.tools.android.startHandOff
import app.whittle.tools.android.text
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * Opens Android's share sheet with text or a workspace file; the user picks where it goes. A file is shared from a copy
 * in the cache, so the app it goes to never reaches the workspace. Sharing sends data off the phone, so it's always asked.
 */
class ShareTool(private val context: Context, private val workspaces: Workspaces, private val config: PhoneActionsConfig.Share) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val text = arguments.text(TEXT)
        val path = arguments.text(FILE)
        if (text.isEmpty() == path.isEmpty()) return ToolJson.error("give either text or file, not both")
        val send = Intent(Intent.ACTION_SEND)
        if (path.isEmpty()) {
            send.setType(PLAIN_TEXT).putExtra(Intent.EXTRA_TEXT, text)
        } else {
            val chat = context.workspace ?: return ToolJson.error("this chat has no workspace")
            val source = when (val located = workspaces.locate(chat, path)) {
                is Outcome.Failure -> return ToolJson.error(located.error.message)
                is Outcome.Success -> located.value
            }
            if (!source.isFile) return ToolJson.error("$path isn't a file")
            if (source.length() > config.maxFileBytes) return ToolJson.error("$path is larger than ${config.maxFileBytes} bytes")
            val copy = withContext(Dispatchers.IO) { copyForSharing(source) } ?: return ToolJson.error("couldn't prepare $path for sharing")
            val uri = CacheFiles.uriFor(this.context, copy)
            send.setType(typeOf(source)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The chooser passes the grant on only for what's in the clip data.
            send.clipData = ClipData.newRawUri(source.name, uri)
        }
        if (!this.context.startHandOff(Intent.createChooser(send, null))) return ToolJson.error("nothing on this phone can share it")
        return ToolJson.success(Provenance.Trusted, buildJsonObject { put(OPENED, true); put(SENT, false) })
    }

    /** A fresh copy under the share folder, after clearing out the last ones: one shared file is reachable at a time. */
    private fun copyForSharing(source: File): File? = try {
        val directory = File(context.cacheDir, config.directoryName)
        directory.deleteRecursively()
        directory.mkdirs()
        source.copyTo(File(directory, source.name), overwrite = true)
    } catch (exception: IOException) {
        null
    }

    private fun typeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: ANY_TYPE

    companion object {
        private const val TEXT = "text"
        private const val FILE = "file"
        private const val OPENED = "openedShareSheet"
        private const val SENT = "sent"
        private const val PLAIN_TEXT = "text/plain"
        private const val ANY_TYPE = "application/octet-stream"

        val DEFINITION = ToolDefinition(
            id = ToolId("share"),
            description = "Opens the phone's share sheet with text or a file from the workspace; the user picks the app and " +
                "sends it there. Give either text or file.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$TEXT":{"type":"string","description":"Words to share"},""" +
                    """"$FILE":{"type":"string","description":"A file in /workspace, absolute or relative to it"}}}""",
            ).jsonObject,
            riskTier = RiskTier.SEND_OR_SHARE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_share",
            outputProvenance = Provenance.Trusted,
        )
    }
}
