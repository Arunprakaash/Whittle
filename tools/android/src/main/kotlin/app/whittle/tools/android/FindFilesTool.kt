package app.whittle.tools.android

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit

/** What find_files may spend: lists hold [maxResults], the whole call stops after [scanMillis], at most [maxHashBytes] are read to find copies. */
data class FindFilesLimits(val maxResults: Int, val scanMillis: Long, val maxHashBytes: Long)

/** Extensions per kind of file (keys `image`, `video`, `audio`, `document`, `apk`, `archive`); anything else is [OTHER]. */
class FileTypes(private val extensions: Map<String, List<String>>) {
    private val byExtension: Map<String, String> =
        extensions.flatMap { (type, list) -> list.map { it.lowercase() to type } }.toMap()

    val names: Set<String> get() = extensions.keys + OTHER

    fun typeOf(fileName: String): String = byExtension[fileName.substringAfterLast('.', "").lowercase()] ?: OTHER

    companion object {
        const val OTHER = "other"
    }
}

/** A file passes when it is at least [minBytes], last changed before [olderThanMillis] (if set) and of one of [types] (if any). */
data class FileFilter(val minBytes: Long = 0, val olderThanMillis: Long? = null, val types: Set<String> = emptySet())

data class FoundFile(val file: File, val bytes: Long, val modified: Long, val type: String)

/** [files] are largest first; [partial] means the walk ran out of time and there may be more. */
data class FindResult(val files: List<FoundFile>, val partial: Boolean)

/**
 * The files under [folder] that pass [filter], largest first. Symlinks aren't followed and unreadable folders are
 * skipped. The walk stops when [now] reaches [deadlineMillis] (checked at every entry) and is then partial.
 */
fun findFiles(folder: File, filter: FileFilter, types: FileTypes, deadlineMillis: Long, now: () -> Long): FindResult {
    val found = mutableListOf<FoundFile>()
    var partial = false
    fun walk(dir: File) {
        for (entry in dir.listFiles().orEmpty()) {
            if (partial) return
            if (now() >= deadlineMillis) {
                partial = true
                return
            }
            if (Files.isSymbolicLink(entry.toPath())) continue
            if (entry.isDirectory) {
                walk(entry)
                continue
            }
            val bytes = entry.length()
            val modified = entry.lastModified()
            val type = types.typeOf(entry.name)
            if (bytes < filter.minBytes) continue
            if (filter.olderThanMillis != null && modified >= filter.olderThanMillis) continue
            if (filter.types.isNotEmpty() && type !in filter.types) continue
            found += FoundFile(entry, bytes, modified, type)
        }
    }
    walk(folder)
    return FindResult(found.sortedByDescending { it.bytes }, partial)
}

/** What the user and model call shared storage's top. */
private const val SDCARD = "/sdcard"

/**
 * [path] (`/sdcard/...` or relative to it; null or empty means the top) under [root]'s real location, with links not yet
 * resolved, or null when it contains `..` or starts anywhere but shared storage. Callers resolve links and check it
 * still lies inside [root], since a link can point anywhere.
 */
fun storagePath(root: File, path: String?): File? {
    val text = path?.trim().orEmpty()
    if (text.split('/', '\\').any { it == ".." }) return null
    val relative = when {
        text.isEmpty() -> ""
        text == SDCARD -> ""
        text.startsWith("$SDCARD/") -> text.removePrefix("$SDCARD/")
        text.startsWith("/") -> return null
        else -> text
    }
    return File(root.canonicalFile, relative)
}

/** True when [file], links resolved, is [root] or lies under it. */
fun isInside(root: File, file: File): Boolean = file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())

/**
 * [folder] (`/sdcard/...` or relative to it; null means the top) as a real directory, or null when it doesn't exist,
 * contains `..`, or - symlinks resolved - lies outside [root].
 */
fun resolveFolder(root: File, folder: String?): File? {
    val target = storagePath(root, folder)?.canonicalFile ?: return null
    return target.takeIf { isInside(root, it) && it.isDirectory }
}

/** [file] relative to [root]'s real location, `/`-separated; empty for the top itself. */
fun relativePath(root: File, file: File): String = file.relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')

/** [file] as `/sdcard/...`, so the model never sees the phone's real storage path. */
fun displayPath(root: File, file: File): String {
    val relative = relativePath(root, file)
    return if (relative.isEmpty()) SDCARD else "$SDCARD/$relative"
}

/**
 * Finds big, old or duplicate files in shared storage. File names are whatever apps and websites chose, so they are
 * untrusted. One time budget and one hashing budget cover the whole call; anything cut short says `partial`.
 */
class FindFilesTool(
    private val storage: SharedStorage,
    private val limits: FindFilesLimits,
    private val types: FileTypes,
    private val clock: EpochClock,
) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val root = storage.root() ?: return ToolJson.missing(AndroidCapabilities.STORAGE)
        if (!withContext(Dispatchers.IO) { storage.canRead() }) return ToolJson.error("shared storage can't be read")
        val folder = resolveFolder(root, arguments[FOLDER]?.jsonPrimitive?.contentOrNull)
            ?: return ToolJson.error("folder must be an existing folder inside shared storage, like /sdcard/Download")
        val minBytes = arguments[MIN_BYTES]?.jsonPrimitive?.longOrNull ?: 0L
        val olderDays = arguments[OLDER_THAN_DAYS]?.jsonPrimitive?.longOrNull
        if (minBytes < 0 || (olderDays != null && olderDays < 0)) return ToolJson.error("minBytes and olderThanDays can't be negative")
        val wanted = (arguments[TYPES] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.toSet()
        val unknown = wanted - types.names
        if (unknown.isNotEmpty()) return ToolJson.error("unknown types ${unknown.sorted()}; use any of ${types.names.sorted()}")
        val duplicates = arguments[DUPLICATES]?.jsonPrimitive?.booleanOrNull == true

        val body = withContext(Dispatchers.IO) {
            // One deadline for the walk and the hashing together, so the call never runs past scanMillis.
            val deadline = clock.nowMillis() + limits.scanMillis
            val cutoff = olderDays?.let { clock.nowMillis() - TimeUnit.DAYS.toMillis(it) }
            val found = findFiles(folder, FileFilter(minBytes, cutoff, wanted), types, deadline, clock::nowMillis)
            buildJsonObject {
                if (duplicates) putDuplicates(root, found, deadline) else putFiles(root, found)
            }
        }
        return ToolJson.success(PROVENANCE, body)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putFiles(root: File, found: FindResult) {
        put(MATCHED, found.files.size)
        putJsonArray(FILES) {
            found.files.take(limits.maxResults).forEach { file ->
                addJsonObject {
                    put(PATH, displayPath(root, file.file))
                    put(BYTES, file.bytes)
                    put(MODIFIED, Instant.ofEpochMilli(file.modified).toString())
                    put(TYPE, file.type)
                }
            }
        }
        if (found.partial) put(PARTIAL, true)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putDuplicates(root: File, found: FindResult, deadline: Long) {
        val result = findDuplicates(found.files.map { it.file }, deadline, clock::nowMillis, limits.maxHashBytes)
        val groups = result.groups
            .map { group -> group to group.first().length() * (group.size - 1) }
            .sortedByDescending { it.second }
        put(MATCHED, groups.size)
        put(WASTED, groups.sumOf { it.second })
        // maxResults bounds the paths across all groups, not just the groups, so one group of thousands of copies can't flood the answer.
        var pathsLeft = limits.maxResults
        // Groups past the cap are never listed, so all their paths count as left out.
        var omitted = groups.drop(limits.maxResults).sumOf { it.first.size }
        putJsonArray(GROUPS) {
            groups.take(limits.maxResults).forEach { (group, wasted) ->
                val shown = group.map { displayPath(root, it) }.sorted().take(pathsLeft)
                pathsLeft -= shown.size
                omitted += group.size - shown.size
                addJsonObject {
                    put(BYTES, group.first().length())
                    put(COPIES, group.size)
                    put(WASTED, wasted)
                    putJsonArray(PATHS) { shown.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        if (omitted > 0) put(PATHS_OMITTED, omitted)
        if (found.partial || result.partial) put(PARTIAL, true)
    }

    companion object {
        private const val FOLDER = "folder"
        private const val MIN_BYTES = "minBytes"
        private const val OLDER_THAN_DAYS = "olderThanDays"
        private const val TYPES = "types"
        private const val DUPLICATES = "duplicates"
        private const val MATCHED = "matched"
        private const val FILES = "files"
        private const val GROUPS = "groups"
        private const val PATHS = "paths"
        private const val PATHS_OMITTED = "pathsOmitted"
        private const val COPIES = "copies"
        private const val PATH = "path"
        private const val BYTES = "bytes"
        private const val WASTED = "wastedBytes"
        private const val MODIFIED = "modified"
        private const val TYPE = "type"
        private const val PARTIAL = "partial"
        private val PROVENANCE = Provenance.Untrusted("your files")

        val DEFINITION = ToolDefinition(
            id = ToolId("find_files"),
            description = "Finds files in shared storage, largest first, optionally only in one folder, at least minBytes, " +
                "not changed for olderThanDays days, or of the given types. Paths start with /sdcard. With duplicates=true it lists " +
                "groups of identical files instead, biggest waste first, with wastedBytes (the space freed by keeping one copy). " +
                "matched is how many there were in all; each group says how many copies it has, and pathsOmitted counts paths left out to keep the answer short; partial=true means the search ran out of time or budget, so there may be more. " +
                "It never deletes anything.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$FOLDER":{"type":"string","description":"Folder to search, e.g. /sdcard/Download. Defaults to all of shared storage."},""" +
                    """"$MIN_BYTES":{"type":"integer","description":"Only files at least this big"},""" +
                    """"$OLDER_THAN_DAYS":{"type":"integer","description":"Only files last changed more than this many days ago"},""" +
                    """"$TYPES":{"type":"array","items":{"type":"string","enum":["image","video","audio","document","apk","archive","other"]}},""" +
                    """"$DUPLICATES":{"type":"boolean","description":"List groups of identical files instead"}}}""",
            ).jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = setOf(AndroidCapabilities.STORAGE),
            consentTextKey = "consent_find_files",
            outputProvenance = PROVENANCE,
            exposesPersonalData = true,
        )
    }
}
