package app.whittle.tools.android

import android.app.usage.StorageStatsManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import app.whittle.core.common.EpochClock
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.TimeUnit

/** What the overview may spend: the biggest [maxFolders] are listed, scanning stops after [scanMillis], "old" means [oldDays]. */
data class StorageLimits(val maxFolders: Int, val scanMillis: Long, val oldDays: Int)

/**
 * Answers "what's using my storage": total and free space, the biggest folders, what photos, video and audio take,
 * and how much sits in Downloads untouched. Sizes only; it never reads a file's contents.
 */
class StorageOverviewTool(
    private val context: Context,
    private val storage: SharedStorage,
    private val limits: StorageLimits,
    private val clock: EpochClock,
) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val root = storage.root() ?: return ToolJson.missing(AndroidCapabilities.STORAGE)
        if (!root.canRead()) return ToolJson.error("shared storage can't be read")
        val body = withContext(Dispatchers.IO) {
            val folders = topFolders(root, limits.maxFolders, clock.nowMillis() + limits.scanMillis, clock::nowMillis)
            val media = mediaBytes()
            val oldCutoff = clock.nowMillis() - TimeUnit.DAYS.toMillis(limits.oldDays.toLong())
            val downloads = bytesOlderThan(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), oldCutoff)
            buildJsonObject {
                totals()?.let { (total, free) ->
                    put(TOTAL, total)
                    put(FREE, free)
                }
                putJsonArray(FOLDERS) {
                    folders.forEach { folder ->
                        addJsonObject {
                            put(NAME, folder.name)
                            put(BYTES, folder.bytes)
                            put(IS_FOLDER, folder.isDirectory)
                            if (folder.partial) put(PARTIAL, true)
                        }
                    }
                }
                putJsonObject(MEDIA) { media.forEach { (kind, bytes) -> put(kind, bytes) } }
                put(OLD_DOWNLOADS, downloads)
            }
        }
        return ToolJson.success(PROVENANCE, body)
    }

    private fun totals(): Pair<Long, Long>? = runCatching {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
    }.getOrNull()

    /** A kind whose query fails is left out rather than failing the whole answer. */
    private fun mediaBytes(): Map<String, Long> = buildMap {
        mediaSum(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)?.let { put(IMAGES, it) }
        mediaSum(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)?.let { put(VIDEO, it) }
        mediaSum(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)?.let { put(AUDIO, it) }
    }

    private fun mediaSum(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            var sum = 0L
            while (cursor.moveToNext()) sum += cursor.getLong(0)
            sum
        }
    }.getOrNull()

    companion object {
        private const val TOTAL = "totalBytes"
        private const val FREE = "freeBytes"
        private const val FOLDERS = "folders"
        private const val NAME = "name"
        private const val BYTES = "bytes"
        private const val IS_FOLDER = "isFolder"
        private const val PARTIAL = "partial"
        private const val MEDIA = "media"
        private const val IMAGES = "images"
        private const val VIDEO = "video"
        private const val AUDIO = "audio"
        private const val OLD_DOWNLOADS = "downloadsOlderThan90Days"
        private val PROVENANCE = Provenance.Trusted

        val DEFINITION = ToolDefinition(
            id = ToolId("storage_overview"),
            description = "Shows what is using the phone's storage: total and free bytes, the largest folders (partial=true means " +
                "the size is at least that much), bytes used by images, video and audio, and bytes in Downloads older than 90 days. " +
                "Sizes only, no file contents.",
            inputSchema = Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = setOf(AndroidCapabilities.STORAGE),
            consentTextKey = "consent_storage_overview",
            outputProvenance = PROVENANCE,
        )
    }
}
