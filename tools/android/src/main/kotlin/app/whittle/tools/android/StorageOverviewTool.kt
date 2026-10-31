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
import java.io.File
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
        if (!withContext(Dispatchers.IO) { storage.canRead() }) return ToolJson.error("shared storage can't be read")
        val body = withContext(Dispatchers.IO) {
            // One budget for everything, so folders, media and Downloads together never run past scanMillis.
            val deadline = clock.nowMillis() + limits.scanMillis
            // Cheapest first: the folder walk can use the whole budget, and the figures people ask for must not read 0 then.
            val media = mediaBytes(deadline)
            val oldCutoff = clock.nowMillis() - TimeUnit.DAYS.toMillis(limits.oldDays.toLong())
            val downloads = bytesOlderThan(File(root, Environment.DIRECTORY_DOWNLOADS), oldCutoff, deadline, clock::nowMillis)
            val folders = topFolders(root, limits.maxFolders, deadline, clock::nowMillis)
            buildJsonObject {
                totals()?.let { (total, free) ->
                    put(TOTAL, total)
                    put(FREE, free)
                }
                putJsonArray(FOLDERS) {
                    folders.folders.forEach { folder ->
                        addJsonObject {
                            put(NAME, folder.name)
                            put(BYTES, folder.bytes)
                            put(IS_FOLDER, folder.isDirectory)
                            if (folder.partial) put(PARTIAL, true)
                        }
                    }
                }
                if (folders.partial) put(FOLDERS_PARTIAL, true)
                putJsonObject(MEDIA) { media.forEach { (kind, size) -> put(kind, size.bytes) } }
                if (media.values.any { it.partial }) put(MEDIA_PARTIAL, true)
                put(OLD_DOWNLOADS, downloads.bytes)
                put(OLD_DAYS, limits.oldDays)
                if (downloads.partial) put(OLD_DOWNLOADS_PARTIAL, true)
            }
        }
        return ToolJson.success(PROVENANCE, body)
    }

    private fun totals(): Pair<Long, Long>? = runCatching {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
    }.getOrNull()

    /** A kind whose query fails is left out rather than failing the whole answer; one cut short by [deadline] is partial. */
    private fun mediaBytes(deadline: Long): Map<String, Measured> = buildMap {
        mediaSum(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, deadline)?.let { put(IMAGES, it) }
        mediaSum(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, deadline)?.let { put(VIDEO, it) }
        mediaSum(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, deadline)?.let { put(AUDIO, it) }
    }

    private fun mediaSum(uri: Uri, deadline: Long): Measured? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            var sum = 0L
            var partial = false
            while (cursor.moveToNext()) {
                if (clock.nowMillis() >= deadline) {
                    partial = true
                    break
                }
                sum += cursor.getLong(0)
            }
            Measured(sum, partial)
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
        private const val FOLDERS_PARTIAL = "foldersPartial"
        private const val MEDIA = "media"
        private const val IMAGES = "images"
        private const val VIDEO = "video"
        private const val AUDIO = "audio"
        private const val MEDIA_PARTIAL = "mediaPartial"
        private const val OLD_DOWNLOADS = "downloadsOld"
        private const val OLD_DAYS = "oldDays"
        private const val OLD_DOWNLOADS_PARTIAL = "downloadsOldPartial"
        private val PROVENANCE = Provenance.Untrusted("your files")

        val DEFINITION = ToolDefinition(
            id = ToolId("storage_overview"),
            description = "Shows what is using the phone's storage: total and free bytes, the largest folders (partial=true means " +
                "the size is at least that much; foldersPartial=true means the scan ran out of time, so some folders were not measured and the biggest may be missing from the list), bytes used by images, video and audio, and bytes in Downloads older than oldDays days (downloadsOld). " +
                "Any figure marked partial (downloadsOldPartial, mediaPartial) is at least that much. Sizes only, no file contents.",
            inputSchema = Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = setOf(AndroidCapabilities.STORAGE),
            consentTextKey = "consent_storage_overview",
            outputProvenance = PROVENANCE,
        )
    }
}
