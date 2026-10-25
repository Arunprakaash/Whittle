package app.whittle.tools.android

import android.content.Context
import android.media.MediaScannerConnection
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ApprovalPreview
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
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
import java.util.Locale

/** Tells Android's media index which files changed, so galleries and file apps stop showing deleted ones. */
fun interface MediaScan {
    fun scan(paths: List<String>)
}

/** The media index on the phone; the scan runs in the background and nobody waits for it. */
class DeviceMediaScan(private val context: Context) : MediaScan {
    override fun scan(paths: List<String>) = MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
}

/** What one delete_files call may take: at most [maxPaths] paths; [notDeleted] marks a refused path on the card. */
data class DeleteFilesLimits(val maxPaths: Int, val notDeleted: String)

/**
 * Deletes files in shared storage for good. The policy engine asks before every call, and the card lists what [check]
 * finds for each path the model sent: the file's real `/sdcard/...` path and size, or why it won't be deleted. Only
 * those may go: each must name a regular file inside shared storage by its own path. Folders, links, paths through a
 * linked folder, `..` and anything outside are refused, one by one. [execute] checks again, so the card is display only.
 */
class DeleteFilesTool(
    private val storage: SharedStorage,
    private val limits: DeleteFilesLimits,
    private val mediaScan: MediaScan,
) : ApprovalPreview {
    override val definition = DEFINITION

    override suspend fun preview(arguments: JsonObject): Map<String, String> = withContext(Dispatchers.IO) {
        val paths = when (val request = request(arguments)) {
            is Request.Invalid -> return@withContext card(listOf("${limits.notDeleted}: ${request.reason}"), emptyList())
            is Request.Paths -> request.paths
        }
        val root = storage.root() ?: return@withContext card(paths.map { refusedLine(it, NO_ACCESS) }, emptyList())
        val seen = mutableSetOf<File>()
        val checks = paths.map { path -> path to check(root, path, seen) }
        val lines = checks.map { (path, check) ->
            when (check) {
                is Check.Ready -> "${displayPath(root, check.file)}$GAP${readableSize(check.bytes)}"
                is Check.Refused -> refusedLine(path, check.reason)
            }
        }
        card(lines, checks.mapNotNull { (it.second as? Check.Ready)?.bytes })
    }

    /** A control character in a refused path shows as `?`, so the line can't break into what looks like another file. */
    private fun refusedLine(path: String, reason: String) =
        "${path.map { if (it.isISOControl()) '?' else it }.joinToString("")}$GAP${limits.notDeleted}: $reason"

    private fun card(lines: List<String>, sizes: List<Long>) =
        mapOf(LISTING to lines.joinToString("\n"), COUNT to sizes.size.toString(), TOTAL to readableSize(sizes.sum()))

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val root = storage.root() ?: return ToolJson.missing(AndroidCapabilities.STORAGE)
        val paths = when (val request = request(arguments)) {
            is Request.Invalid -> return ToolJson.error(request.reason)
            is Request.Paths -> request.paths
        }

        val body = withContext(Dispatchers.IO) {
            val seen = mutableSetOf<File>()
            val results = paths.map { path ->
                when (val check = check(root, path, seen)) {
                    is Check.Refused -> Result(path, REFUSED, check.reason)
                    is Check.Ready -> if (check.file.delete()) Result(path, DELETED, file = check.file, bytes = check.bytes) else Result(path, FAILED, "Android didn't let it be deleted")
                }
            }
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

    private sealed interface Request {
        data class Paths(val paths: List<String>) : Request

        data class Invalid(val reason: String) : Request
    }

    /** Anything but a short list of strings would show differently on the card than it deletes, so nothing goes. */
    private fun request(arguments: JsonObject): Request {
        val items = arguments[PATHS] as? JsonArray ?: return Request.Invalid("paths must be a list of files, like [\"/sdcard/Download/a.jpg\"]")
        val paths = items.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return Request.Invalid("every entry in paths must be a file path") }
        return when {
            paths.isEmpty() -> Request.Invalid("paths is empty")
            paths.size > limits.maxPaths -> Request.Invalid("at most ${limits.maxPaths} files per call, so the user can review them; split the list into several calls")
            else -> Request.Paths(paths)
        }
    }

    private sealed interface Check {
        data class Ready(val file: File, val bytes: Long) : Check

        data class Refused(val reason: String) : Check
    }

    private class Result(val path: String, val status: String, val reason: String? = null, val file: File? = null, val bytes: Long = 0)

    /** Whether [path] names a file that may be deleted; [seen] collects the files already taken in this call. */
    private fun check(root: File, path: String, seen: MutableSet<File>): Check {
        // A line break would make one name read as two files on the card.
        if (path.any(Char::isISOControl)) return Check.Refused("has a line break or control character in it")
        val literal = storagePath(root, path) ?: return Check.Refused("must be a file in shared storage, like /sdcard/Download/a.jpg, with no ..")
        // Not following links: a link to a file elsewhere must never delete that file.
        val attributes = try {
            Files.readAttributes(literal.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (missing: NoSuchFileException) {
            return Check.Refused("doesn't exist")
        } catch (unreadable: IOException) {
            return Check.Refused("can't be read")
        }
        when {
            attributes.isSymbolicLink -> return Check.Refused("is a link, not a file")
            attributes.isDirectory -> return Check.Refused("is a folder; only files are deleted")
            !attributes.isRegularFile -> return Check.Refused("isn't a regular file")
        }
        // A linked folder on the way would delete a file the card never showed.
        val file = literal.canonicalFile
        if (!isInside(root, file) || file.toPath() != literal.toPath().normalize()) return Check.Refused("goes through a link")
        if (!seen.add(file)) return Check.Refused("is listed twice")
        return Check.Ready(file, attributes.size())
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
        private const val NO_ACCESS = "Whittle can't see your files yet"
        private const val GAP = "  ·  "

        /** The card's placeholders this tool fills: one line per path, how many files will go, and their total size. */
        const val LISTING = "listing"
        const val COUNT = "count"
        const val TOTAL = "total"
        val PREVIEW = setOf(LISTING, COUNT, TOTAL)
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

/** [bytes] as file managers write it (1 KB is 1000 bytes), e.g. 1.2 MB. */
fun readableSize(bytes: Long): String {
    if (bytes < SIZE_STEP) return "$bytes B"
    var value = bytes.toDouble()
    var unit = -1
    while (value >= SIZE_STEP && unit < SIZE_UNITS.lastIndex) {
        value /= SIZE_STEP
        unit++
    }
    return String.format(Locale.ROOT, if (value < 10) "%.1f %s" else "%.0f %s", value, SIZE_UNITS[unit])
}

private const val SIZE_STEP = 1000.0
private val SIZE_UNITS = listOf("KB", "MB", "GB", "TB")
