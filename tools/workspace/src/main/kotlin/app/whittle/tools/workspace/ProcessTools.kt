package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

/** A process's output is whatever it printed, which may come from the web or files, so it's untrusted. */
private val PROCESS_OUTPUT = Provenance.Untrusted("process output")

/** Shared by the process tools: each sees only its own chat's processes, and describes one the same way. */
abstract class ProcessTool(workspaces: Workspaces, protected val processes: ProcessManager) : WorkspaceTool(workspaces) {
    protected fun ofThisChat(id: String?, access: WorkspaceAccess): ProcessInfo? =
        id?.let { processes.info(it) }?.takeIf { it.workspace == access.id }

    protected fun JsonObjectBuilder.describe(info: ProcessInfo) {
        put(ID, info.id)
        put(NAME, info.name)
        put(STATUS, info.status.name.lowercase())
        info.port?.let { put(PORT, it) }
        if (info.listening) info.url?.let { put(URL, it) }
        info.exitCode?.let { put(EXIT_CODE, it) }
    }

    protected companion object {
        const val ID = "id"
        const val NAME = "name"
        const val COMMAND = "command"
        const val PORT = "port"
        const val LINES = "lines"
        const val STATUS = "status"
        const val URL = "url"
        const val EXIT_CODE = "exitCode"
        const val OUTPUT = "output"
        const val PROCESSES = "processes"
        const val UPTIME_SECONDS = "uptimeSeconds"
        const val MILLIS_PER_SECOND = 1_000L
        const val NOT_FOUND = "no such process in this chat; list_processes shows the ones there are"
    }
}

/** Starts a server or watcher that keeps running; the user can open what it serves in the live preview. */
class StartProcessTool(workspaces: Workspaces, processes: ProcessManager) : ProcessTool(workspaces, processes) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val name = arguments.text(NAME)?.takeIf { it.isNotBlank() } ?: return error("name is empty")
        val command = arguments.text(COMMAND)?.takeIf { it.isNotBlank() } ?: return error("command is empty")
        val directory = when (val root = access.root()) {
            is Outcome.Failure -> return error(root.error.message)
            is Outcome.Success -> root.value
        }
        val info = when (val started = processes.start(access.id, directory, name, command, arguments.int(PORT))) {
            is Outcome.Failure -> return error(started.error.message)
            is Outcome.Success -> started.value
        }
        val body = buildJsonObject {
            describe(info)
            put(OUTPUT, processes.output(info.id).orEmpty())
        }
        // A server that ended on its own didn't start; its output says why.
        return ToolOutput(body.toString(), isError = !info.live, provenance = PROCESS_OUTPUT)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            id = ToolId("start_process"),
            description = "Starts a command that keeps running after this call, in Linux in /workspace like run_command, with the " +
                "network: a dev server (npm run dev, vite, python3 -m http.server), a backend, or a watcher. Give the port it " +
                "serves on; once something answers there, the user gets a card with Preview. Replies when it answers, when the " +
                "command ends, or after a short wait, with its latest output. Starting one with the same name again restarts it. " +
                "Servers must listen on 127.0.0.1 or 0.0.0.0.",
            inputSchema = schema(
                """{"type":"object","properties":{""" +
                    """"$NAME":{"type":"string","description":"Short name for the user, e.g. notes-app"},""" +
                    """"$COMMAND":{"type":"string","description":"One shell command line"},""" +
                    """"$PORT":{"type":"integer","description":"The port it serves on, if it's a server"}""" +
                    """},"required":["$NAME","$COMMAND"]}""",
            ),
            riskTier = RiskTier.NETWORK_OUT,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_start_process",
            outputProvenance = PROCESS_OUTPUT,
        )
    }
}

/** The latest output of a process, and how it's doing. */
class ReadProcessOutputTool(workspaces: Workspaces, processes: ProcessManager) : ProcessTool(workspaces, processes) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val info = ofThisChat(arguments.text(ID), access) ?: return error(NOT_FOUND)
        val lines = arguments.int(LINES)?.takeIf { it > 0 }
        val output = (if (lines != null) processes.output(info.id, lines) else processes.output(info.id)).orEmpty()
        return success(buildJsonObject { describe(info); put(OUTPUT, output) }, PROCESS_OUTPUT)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            id = ToolId("read_process_output"),
            description = "Shows the latest output of a process started with start_process, and whether it's still running.",
            inputSchema = schema(
                """{"type":"object","properties":{""" +
                    """"$ID":{"type":"string","description":"The process id start_process gave"},""" +
                    """"$LINES":{"type":"integer","description":"How many of the latest lines"}""" +
                    """},"required":["$ID"]}""",
            ),
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_read_process_output",
            outputProvenance = PROCESS_OUTPUT,
        )
    }
}

/** What this chat has running, and what ended. */
class ListProcessesTool(workspaces: Workspaces, processes: ProcessManager, private val clock: () -> Long = System::currentTimeMillis) :
    ProcessTool(workspaces, processes) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val now = clock()
        val mine = processes.processes.value.filter { it.workspace == access.id }
        return success(
            buildJsonObject {
                putJsonArray(PROCESSES) {
                    mine.forEach { info ->
                        addJsonObject {
                            describe(info)
                            put(COMMAND, info.command)
                            put(UPTIME_SECONDS, ((info.endedAtMillis ?: now) - info.startedAtMillis) / MILLIS_PER_SECOND)
                        }
                    }
                }
            },
            PROCESS_OUTPUT,
        )
    }

    companion object {
        val DEFINITION = ToolDefinition(
            id = ToolId("list_processes"),
            description = "Lists the processes started in this chat with start_process: running, and ended.",
            inputSchema = schema("""{"type":"object","properties":{}}"""),
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_list_processes",
            outputProvenance = PROCESS_OUTPUT,
        )
    }
}

/** Stops a process and everything it started. */
class StopProcessTool(workspaces: Workspaces, processes: ProcessManager) : ProcessTool(workspaces, processes) {
    override val definition = DEFINITION

    override suspend fun run(arguments: JsonObject, access: WorkspaceAccess): ToolOutput {
        val info = ofThisChat(arguments.text(ID), access) ?: return error(NOT_FOUND)
        val stopped = processes.stop(info.id) ?: return error(NOT_FOUND)
        return success(buildJsonObject { describe(stopped) })
    }

    companion object {
        val DEFINITION = ToolDefinition(
            id = ToolId("stop_process"),
            description = "Stops a process started in this chat, and everything it started.",
            inputSchema = schema("""{"type":"object","properties":{"$ID":{"type":"string","description":"The process id"}},"required":["$ID"]}"""),
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_stop_process",
            outputProvenance = Provenance.Trusted,
        )
    }
}
