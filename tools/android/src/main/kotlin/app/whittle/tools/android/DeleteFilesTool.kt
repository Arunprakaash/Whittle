package app.whittle.tools.android

import android.content.Context
import android.media.MediaScannerConnection
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ApprovedItem
import app.whittle.tools.api.CallPreview
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
import java.util.Locale

/** Tells Android's media index which files changed, so galleries and file apps stop showing deleted ones. */
fun interface MediaScan {
    fun scan(paths: List<String>)
}

/** The media index on the phone; the scan runs in the background and nobody waits for it. */
class DeviceMediaScan(private val context: Context) : MediaScan {
    override fun scan(paths: List<String>) = MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
}

/**
 * What one delete_files call may take: at most [maxPaths] paths, and nothing under [protectedFolders] (relative to
 * shared storage, matched without regard to case), where other apps keep their own files.
 */
data class DeleteFilesLimits(val maxPaths: Int, val protectedFolders: List<String>) {
    private val protectedPrefixes = protectedFolders.map { it.trim('/').lowercase(Locale.ROOT) }

    /** Whether [relative] (a path under shared storage, `/`-separated) is or lies under a protected folder. */
    fun isProtected(relative: String): Boolean {
        val path = relative.lowercase(Locale.ROOT)
        return protectedPrefixes.any { path == it || path.startsWith("$it/") }
    }
}

/**
 * What delete_files says, on its card and to the model (from agent-messages.json): [notDeleted] marks a file that won't
 * go, [gap] sits between a file and its size or reason, [tooMany] is already filled with the limit, and the rest are
 * reasons a call or a file is refused.
 */
data class DeleteFilesWords(
    val notDeleted: String,
    val gap: String,
    val noAccess: String,
    val notAList: String,
    val notAPath: String,
    val empty: String,
    val tooMany: String,
    val noApprovedList: String,
    val controlCharacter: String,
    val outsideStorage: String,
    val missing: String,
    val unreadable: String,
    val link: String,
    val folder: String,
    val notRegular: String,
    val throughLink: String,
    val listedTwice: String,
    val protectedFolder: String,
    val notOnCard: String,
    val changed: String,
    val refusedByAndroid: String,
)

/**
 * Deletes files in shared storage for good. The policy engine asks before every call, and the card lists what [check]
 * finds for each path the model sent: the file's real `/sdcard/...` path and size, or why it won't be deleted. Only
 * those may go: each must name a regular file inside shared storage by its own path. Folders, links, paths through a
 * linked folder, `..`, anything outside and anything in a protected folder (other apps' files) are refused, one by one. [execute] checks again and deletes only files the
 * approved card listed as going ([ToolExecutionContext.approved]), and only while each has the size and modified time
 * the card saw: a file that appears after the card stays, and so does one replaced or changed while the card waited.
 */
class DeleteFilesTool(
    private val storage: SharedStorage,
    private val limits: DeleteFilesLimits,
    private val words: DeleteFilesWords,
    private val mediaScan: MediaScan,
) : Tool {
    override val definition = DEFINITION

    override suspend fun preview(arguments: JsonObject): CallPreview = withContext(Dispatchers.IO) {
        val paths = when (val request = request(arguments)) {
            is Request.Invalid -> return@withContext card(listOf(shown("${words.notDeleted}: ${request.reason}")), emptyList())
            is Request.Paths -> request.paths
        }
        val root = storage.root() ?: return@withContext card(paths.map { refusedLine(it, words.noAccess) }, emptyList())
        val seen = mutableSetOf<File>()
        val checks = paths.map { path -> path to check(root, path, seen) }
        val lines = checks.map { (path, check) ->
            when (check) {
                is Check.Ready -> shown("${displayPath(root, check.file)}${words.gap}${readableSize(check.bytes)}")
                is Check.Refused -> refusedLine(path, check.reason)
            }
        }
        card(lines, checks.mapNotNull { it.second as? Check.Ready })
    }

    private fun refusedLine(path: String, reason: String) = shown("$path${words.gap}${words.notDeleted}: $reason")

    /** The card's values, and the files an approval of it covers. With none, the card says nothing will be deleted. */
    private fun card(lines: List<String>, ready: List<Check.Ready>) = CallPreview(
        card = mapOf(LISTING to lines.joinToString("\n"), COUNT to ready.size.toString(), TOTAL to readableSize(ready.sumOf { it.bytes })),
        approved = ready.map { ApprovedItem(it.file.path, it.bytes, it.modified) },
        variant = NOTHING.takeIf { ready.isEmpty() },
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val root = storage.root() ?: return ToolJson.missing(AndroidCapabilities.STORAGE)
        val paths = when (val request = request(arguments)) {
            is Request.Invalid -> return ToolJson.error(request.reason)
            is Request.Paths -> request.paths
        }
        // Only files the approved card listed as going may go; without that list, nothing does.
        val approved = context.approved?.associateBy { it.id }
            ?: return ToolJson.error(words.noApprovedList)

        val body = withContext(Dispatchers.IO) {
            val seen = mutableSetOf<File>()
            val results = paths.map { path ->
                when (val check = check(root, path, seen)) {
                    is Check.Refused -> Result(path, REFUSED, check.reason)
                    is Check.Ready -> {
                        val listed = approved[check.file.path]
                        when {
                            listed == null -> Result(path, REFUSED, words.notOnCard)
                            // A different file under the same name, or this one changed, isn't what the user saw on the card.
                            !check.matches(listed) -> Result(path, REFUSED, words.changed)
                            check.file.delete() -> Result(path, DELETED, file = check.file, bytes = check.bytes)
                            else -> Result(path, FAILED, words.refusedByAndroid)
                        }
                    }
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
        val items = arguments[PATHS] as? JsonArray ?: return Request.Invalid(words.notAList)
        val paths = items.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return Request.Invalid(words.notAPath) }
        return when {
            paths.isEmpty() -> Request.Invalid(words.empty)
            paths.size > limits.maxPaths -> Request.Invalid(words.tooMany)
            else -> Request.Paths(paths)
        }
    }

    private sealed interface Check {
        data class Ready(val file: File, val bytes: Long, val modified: Long) : Check {
            /** Whether the card's [item] saw this file as it is now: the same size, modified at the same time. */
            fun matches(item: ApprovedItem): Boolean = item.bytes == bytes && item.modifiedMillis == modified
        }

        data class Refused(val reason: String) : Check
    }

    private class Result(val path: String, val status: String, val reason: String? = null, val file: File? = null, val bytes: Long = 0)

    /** Whether [path] names a file that may be deleted; [seen] collects the files already taken in this call. */
    private fun check(root: File, path: String, seen: MutableSet<File>): Check {
        // A line break would make one name read as two files on the card.
        if (path.any(Char::isISOControl)) return Check.Refused(words.controlCharacter)
        val literal = storagePath(root, path) ?: return Check.Refused(words.outsideStorage)
        // Not following links: a link to a file elsewhere must never delete that file.
        val attributes = try {
            Files.readAttributes(literal.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (missing: NoSuchFileException) {
            return Check.Refused(words.missing)
        } catch (unreadable: IOException) {
            return Check.Refused(words.unreadable)
        }
        when {
            attributes.isSymbolicLink -> return Check.Refused(words.link)
            attributes.isDirectory -> return Check.Refused(words.folder)
            !attributes.isRegularFile -> return Check.Refused(words.notRegular)
        }
        // A linked folder on the way would delete a file the card never showed.
        val file = try {
            literal.canonicalFile
        } catch (unreadable: IOException) {
            return Check.Refused(words.unreadable)
        } catch (denied: SecurityException) {
            return Check.Refused(words.unreadable)
        }
        if (!isInside(root, file) || file.toPath() != literal.toPath().normalize()) return Check.Refused(words.throughLink)
        if (limits.isProtected(relativePath(root, file))) return Check.Refused(words.protectedFolder)
        if (!seen.add(file)) return Check.Refused(words.listedTwice)
        return Check.Ready(file, attributes.size(), attributes.lastModifiedTime().toMillis())
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

        /** The card's placeholders this tool fills: one line per path, how many files will go, and their total size. */
        const val LISTING = "listing"
        const val COUNT = "count"
        const val TOTAL = "total"
        val PREVIEW = setOf(LISTING, COUNT, TOTAL)

        /** The card for a call where no file will go: `delete_files.nothing` in the presentation config. */
        const val NOTHING = "nothing"
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

/**
 * [text] with control, format (bidi and the like) and line-separator characters shown as `?`, so a line on a card can't
 * break into what looks like another file, or read in a different order than it is.
 */
fun shown(text: String): String = buildString {
    text.codePoints().forEach { point ->
        val hidden = Character.isISOControl(point) || Character.getType(point).toByte().let {
            it == Character.FORMAT || it == Character.LINE_SEPARATOR || it == Character.PARAGRAPH_SEPARATOR
        }
        if (hidden) append('?') else appendCodePoint(point)
    }
}
