package app.whittle.tools.android

import android.content.Context
import android.media.MediaScannerConnection
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Tells Android's media index which files changed, so galleries and file apps stop showing deleted ones. */
fun interface MediaScan {
    fun scan(paths: List<String>)
}

/** The media index on the phone; the scan runs in the background and nobody waits for it. */
class DeviceMediaScan(private val context: Context) : MediaScan {
    override fun scan(paths: List<String>) = MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
}

/**
 * Deletes files in shared storage for good. The policy engine asks before every call, and the card lists the exact
 * [paths][PATHS] the model sent, so only those may go: each must name a regular file inside shared storage by its own
 * path. Folders, links, paths through a linked folder, `..` and anything outside are refused, one by one, and at most
 * [maxPaths] are taken per call.
 */
class DeleteFilesTool(
    private val storage: SharedStorage,
    private val maxPaths: Int,
    private val mediaScan: MediaScan,
) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val root = storage.root() ?: return ToolJson.missing(AndroidCapabilities.STORAGE)
        // Anything but a list of strings would show differently on the card than it deletes, so nothing goes.
        val items = arguments[PATHS] as? JsonArray ?: return ToolJson.error("paths must be a list of files, like [\"/sdcard/Download/a.jpg\"]")
        val paths = items.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return ToolJson.error("every entry in paths must be a file path") }
        if (paths.isEmpty()) return ToolJson.error("paths is empty")
        if (paths.size > maxPaths) return ToolJson.error("at most $maxPaths files per call; ask about the rest in another call")

        val body = withContext(Dispatchers.IO) {
            val seen = mutableSetOf<File>()
            val results = paths.map { delete(root, it, seen) }
            val deleted = results.filter { it.status == DELETED }
            if (deleted.isNotEmpty()) mediaScan.scan(deleted.mapNotNull { it.file?.path })
            buildJsonObject {
                put(DELETED, deleted.size)
                put(FREED_BYTES, deleted.sumOf { it.bytes })
                putJsonArray(FILES) {
                    results.forEach { result ->
                        addJsonObject {
                            put(PATH, result.path)
                            put(STATUS, result.status)
                            result.reason?.let { put(REASON, it) }
                        }
                    }
                }
            }
        }
        return ToolJson.success(PROVENANCE, body)
    }

    private class Result(val path: String, val status: String, val reason: String? = null, val file: File? = null, val bytes: Long = 0)

    private fun refused(path: String, reason: String) = Result(path, REFUSED, reason)

    private fun delete(root: File, path: String, seen: MutableSet<File>): Result {
        // A line break would make one name read as two files on the card.
        if (path.any(Char::isISOControl)) return refused(path, "has a line break or control character in it")
        val literal = storagePath(root, path) ?: return refused(path, "must be a file in shared storage, like /sdcard/Download/a.jpg, with no ..")
        // Not following links: a link to a file elsewhere must never delete that file.
        val attributes = try {
            Files.readAttributes(literal.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (missing: NoSuchFileException) {
            return refused(path, "doesn't exist")
        } catch (unreadable: IOException) {
            return refused(path, "can't be read")
        }
        when {
            attributes.isSymbolicLink -> return refused(path, "is a link, not a file")
            attributes.isDirectory -> return refused(path, "is a folder; only files are deleted")
            !attributes.isRegularFile -> return refused(path, "isn't a regular file")
        }
        // A linked folder on the way would delete a file the card never showed.
        val file = literal.canonicalFile
        if (!isInside(root, file) || file.toPath() != literal.toPath().normalize()) return refused(path, "goes through a link")
        if (!seen.add(file)) return refused(path, "is listed twice")
        return if (file.delete()) {
            Result(path, DELETED, file = file, bytes = attributes.size())
        } else {
            Result(path, FAILED, "Android didn't let it be deleted")
        }
    }

    companion object {
        private const val PATHS = "paths"
        private const val FILES = "files"
        private const val PATH = "path"
        private const val STATUS = "status"
        private const val REASON = "reason"
        private const val DELETED = "deleted"
        private const val REFUSED = "refused"
        private const val FAILED = "failed"
        private const val FREED_BYTES = "freedBytes"
        private val PROVENANCE = Provenance.Trusted

        val DEFINITION = ToolDefinition(
            id = ToolId("delete_files"),
            description = "Deletes files in shared storage for good; there is no trash to restore them from. Give paths as " +
                "find_files returns them (/sdcard/...). The user sees every path on a card and approves each call, so list " +
                "only files they chose to remove. Folders, links and anything outside shared storage are refused. Each path " +
                "comes back deleted, refused (with a reason) or failed; deleted is how many went and freedBytes the space freed.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$PATHS":{"type":"array","items":{"type":"string"},"description":"Files to delete, e.g. /sdcard/Download/old.apk"}},""" +
                    """"required":["$PATHS"]}""",
            ).jsonObject,
            riskTier = RiskTier.DELETE_OR_PAY,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = setOf(AndroidCapabilities.STORAGE),
            consentTextKey = "consent_delete_files",
            outputProvenance = PROVENANCE,
        )
    }
}
