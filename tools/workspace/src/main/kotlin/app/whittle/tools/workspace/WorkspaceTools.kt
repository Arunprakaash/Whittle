package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
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
import java.io.File
import java.io.IOException

private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

/** Workspace files may hold anything a command or the web produced, so reading them is untrusted. */
private val FILE_CONTENT = Provenance.Untrusted("workspace files")
private val COMMAND_OUTPUT = Provenance.Untrusted("command output")

/**
 * Runs a shell command in the chat's workspace, inside Linux on Android. Allowed the network it can reach anything,
 * so it is outbound: untrusted models and tainted runs show the exact command on a card first.
 */
class RunCommandTool(workspaces: Workspaces, private val runner: CommandRunner, private val config: TerminalConfig) : WorkspaceTool(workspaces) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val command = arguments.text(COMMAND)?.takeIf { it.isNotBlank() } ?: return error("command is empty")
        val timeout = (arguments.int(TIMEOUT) ?: config.defaultTimeoutSeconds).coerceIn(1, config.maxTimeoutSeconds)
        val directory = when (val root = access.root()) {
            is Outcome.Failure -> return error(root.error.message)
            is Outcome.Success -> root.value
        }
        val network = arguments.flag(NETWORK)
        val result = when (val ran = runner.run(access.command(command), directory, timeout, network)) {
            is Outcome.Failure -> return error(ran.error.message)
            is Outcome.Success -> ran.value
        }
        val body = buildJsonObject {
            result.exitCode?.let { put(EXIT_CODE, it) }
            put(STDOUT, result.stdout)
            if (result.stderr.isNotEmpty()) put(STDERR, result.stderr)
            if (result.timedOut) put(TIMED_OUT_AFTER_SECONDS, timeout)
            if (result.truncated) put(TRUNCATED, true)
        }
        return ToolOutput(body.toString(), isError = result.timedOut || result.exitCode != 0, provenance = COMMAND_OUTPUT)
    }

    companion object {
        private const val COMMAND = "command"
        private const val TIMEOUT = "timeoutSeconds"
        private const val NETWORK = "network"
        private const val EXIT_CODE = "exitCode"
        private const val STDOUT = "stdout"
        private const val STDERR = "stderr"
        private const val TIMED_OUT_AFTER_SECONDS = "timedOutAfterSeconds"
        private const val TRUNCATED = "truncated"

        val DEFINITION = ToolDefinition(
            id = ToolId("run_command"),
            description = "Runs a bash command in Linux (Ubuntu on the phone), in /workspace, the workspace every chat shares. " +
                "Install what you need with apt-get, npm or pip; installed tools stay for every chat. " +
                "The command ends when it finishes or times out, so servers don't keep running. " +
                "Commands are offline: they can't open network connections unless you set $NETWORK to true, which also " +
                "covers local servers and name lookups. Set it only when the command needs it (apt-get, npm install, " +
                "pip install, git clone, downloads, a local server); a socket error saying permission denied means it was " +
                "needed. Prefer the file tools for reading and editing files.",
            inputSchema = schema(
                """{"type":"object","properties":{""" +
                    """"$COMMAND":{"type":"string","description":"One shell command line"},""" +
                    """"$TIMEOUT":{"type":"integer","description":"Seconds before it is stopped"},""" +
                    """"$NETWORK":{"type":"boolean","description":"true only if the command needs the network"}""" +
                    """},"required":["$COMMAND"]}""",
            ),
            riskTier = RiskTier.NETWORK_OUT,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_run_command",
            outputProvenance = COMMAND_OUTPUT,
        )
    }
}

class ListFilesTool(workspaces: Workspaces, private val config: TerminalConfig) : WorkspaceTool(workspaces) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val directory = when (val dir = access.file(arguments.text(PATH) ?: CURRENT)) {
            is Outcome.Failure -> return error(dir.error.message)
            is Outcome.Success -> dir.value
        }
        if (!directory.isDirectory) return error("'${arguments.text(PATH)}' is not a folder")
        val entries = withContext(Dispatchers.IO) { directory.listFiles().orEmpty().filterNot { it.name.startsWith(HIDDEN) }.sortedBy { it.name } }
        return success(
            buildJsonObject {
                putJsonArray(ENTRIES) {
                    entries.take(config.maxListedEntries).forEach { file ->
                        addJsonObject {
                            put(NAME, file.name)
                            put(TYPE, if (file.isDirectory) FOLDER else FILE_TYPE)
                            if (file.isFile) put(SIZE, file.length())
                        }
                    }
                }
                if (entries.size > config.maxListedEntries) put(MORE, entries.size - config.maxListedEntries)
            },
            FILE_CONTENT,
        )
    }

    companion object {
        private const val PATH = "path"
        private const val CURRENT = "."
        private const val HIDDEN = "."
        private const val ENTRIES = "entries"
        private const val NAME = "name"
        private const val TYPE = "type"
        private const val SIZE = "size"
        private const val MORE = "more"
        private const val FOLDER = "folder"
        private const val FILE_TYPE = "file"

        val DEFINITION = ToolDefinition(
            id = ToolId("list_files"),
            description = "Lists the files and folders in a Linux folder. Paths are Linux paths: absolute, or relative to /workspace, the shared workspace.",
            inputSchema = schema("""{"type":"object","properties":{"$PATH":{"type":"string","description":"Folder: absolute, or relative to /workspace; default is /workspace"}}}"""),
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_list_files",
            outputProvenance = FILE_CONTENT,
        )
    }
}

class ReadFileTool(workspaces: Workspaces, private val config: TerminalConfig) : WorkspaceTool(workspaces) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val path = arguments.text(PATH) ?: return error("path is missing")
        val file = when (val resolved = access.file(path)) {
            is Outcome.Failure -> return error(resolved.error.message)
            is Outcome.Success -> resolved.value
        }
        if (!file.isFile) return error("'$path' is not a file")
        val text = try {
            withContext(Dispatchers.IO) { file.readText() }
        } catch (exception: IOException) {
            return error("'$path' can't be read: ${exception.message}")
        }
        if (text.contains(NUL)) return error("'$path' is a binary file")
        return success(
            buildJsonObject {
                put(PATH, path)
                put(CONTENT, text.take(config.maxFileChars))
                if (text.length > config.maxFileChars) put(TRUNCATED_AT, config.maxFileChars)
            },
            FILE_CONTENT,
        )
    }

    companion object {
        private const val PATH = "path"
        private const val CONTENT = "content"
        private const val TRUNCATED_AT = "truncatedAtChars"
        private const val NUL = '\u0000'

        val DEFINITION = ToolDefinition(
            id = ToolId("read_file"),
            description = "Reads a text file from Linux. Paths are absolute, or relative to /workspace, the shared workspace.",
            inputSchema = schema("""{"type":"object","properties":{"$PATH":{"type":"string","description":"Absolute, or relative to /workspace"}},"required":["$PATH"]}"""),
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_read_file",
            outputProvenance = FILE_CONTENT,
        )
    }
}

class WriteFileTool(
    workspaces: Workspaces,
    private val config: TerminalConfig,
    private val changes: FileChanges = FileChanges.NONE,
) : WorkspaceTool(workspaces) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val path = arguments.text(PATH) ?: return error("path is missing")
        val content = arguments.text(CONTENT) ?: return error("content is missing")
        if (content.length > config.maxFileChars) return error("content is longer than ${config.maxFileChars} characters")
        val file = when (val resolved = access.file(path)) {
            is Outcome.Failure -> return error(resolved.error.message)
            is Outcome.Success -> resolved.value
        }
        val before = previousText(file, config.maxFileChars)
        writeText(file, content)?.let { return error(it) }
        changes.record(access.id, file, before)
        return success(buildJsonObject { put(PATH, path); put(CHARACTERS, content.length) })
    }

    companion object {
        private const val PATH = "path"
        private const val CONTENT = "content"
        private const val CHARACTERS = "characters"

        val DEFINITION = ToolDefinition(
            id = ToolId("write_file"),
            description = "Creates or replaces a text file in Linux. Folders are created as needed. Paths are absolute, or relative to /workspace, the shared workspace.",
            inputSchema = schema(
                """{"type":"object","properties":{"$PATH":{"type":"string","description":"Absolute, or relative to /workspace"},""" +
                    """"$CONTENT":{"type":"string","description":"The whole file"}},"required":["$PATH","$CONTENT"]}""",
            ),
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_write_file",
            outputProvenance = Provenance.Trusted,
        )
    }
}

class EditFileTool(
    workspaces: Workspaces,
    private val config: TerminalConfig,
    private val changes: FileChanges = FileChanges.NONE,
) : WorkspaceTool(workspaces) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val path = arguments.text(PATH) ?: return error("path is missing")
        val oldText = arguments.text(OLD_TEXT)?.takeIf { it.isNotEmpty() } ?: return error("oldText is empty")
        val newText = arguments.text(NEW_TEXT) ?: return error("newText is missing")
        val file = when (val resolved = access.file(path)) {
            is Outcome.Failure -> return error(resolved.error.message)
            is Outcome.Success -> resolved.value
        }
        if (!file.isFile) return error("'$path' is not a file")
        val text = try {
            withContext(Dispatchers.IO) { file.readText() }
        } catch (exception: IOException) {
            return error("'$path' can't be read: ${exception.message}")
        }
        // Exactly one match, so an edit never lands somewhere the model didn't mean.
        val matches = text.windowedIndexes(oldText)
        if (matches == 0) return error("oldText was not found in '$path'")
        if (matches > 1) return error("oldText appears $matches times in '$path'; include more surrounding text")
        val updated = text.replaceFirst(oldText, newText)
        if (updated.length > config.maxFileChars) return error("the edited file would be longer than ${config.maxFileChars} characters")
        writeText(file, updated)?.let { return error(it) }
        changes.record(access.id, file, FileChanges.Before.Text(text))
        return success(buildJsonObject { put(PATH, path); put(EDITED, true) })
    }

    private fun String.windowedIndexes(needle: String): Int =
        generateSequence(indexOf(needle)) { previous -> indexOf(needle, previous + 1).takeIf { it >= 0 } }.takeWhile { it >= 0 }.count()

    companion object {
        private const val PATH = "path"
        private const val OLD_TEXT = "oldText"
        private const val NEW_TEXT = "newText"
        private const val EDITED = "edited"

        val DEFINITION = ToolDefinition(
            id = ToolId("edit_file"),
            description = "Replaces one exact piece of text in a file in Linux. oldText must appear exactly once. Paths are absolute, or relative to /workspace.",
            inputSchema = schema(
                """{"type":"object","properties":{"$PATH":{"type":"string","description":"Absolute, or relative to /workspace"},""" +
                    """"$OLD_TEXT":{"type":"string","description":"Exact text to replace"},""" +
                    """"$NEW_TEXT":{"type":"string","description":"Replacement"}},"required":["$PATH","$OLD_TEXT","$NEW_TEXT"]}""",
            ),
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_edit_file",
            outputProvenance = Provenance.Trusted,
        )
    }
}

/** Returns an error message, or null when the file was written. */
private suspend fun writeText(file: File, content: String): String? = try {
    withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
    null
} catch (exception: IOException) {
    "'${file.name}' can't be written: ${exception.message}"
}
