package app.whittle.apps

import app.whittle.agent.AgentMessages
import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.ChatProjects
import app.whittle.tools.workspace.CommandResult
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Saves a long output in the chat's workspace and gives the path it landed at. */
typealias OutputSaver = suspend (WorkspaceId, RunId, ToolCallId, String) -> Outcome<String>

/**
 * Builds a project's app: runs the build script from config in Linux with the network off, signs the unsigned APK here with the app's own key, checks what the built APK declares and returns a short JSON result. The version code goes up
 * (and into whittle-app.json) only when the build succeeds, and only the newest few APKs stay in build/. A chat confined to a
 * project builds only that project.
 */
class BuildAppTool(
    private val workspaces: Workspaces,
    private val projects: ChatProjects,
    private val config: NativeAppsConfig,
    private val messages: AgentMessages,
    private val toolchain: BuildToolchain,
    private val shell: LinuxShell,
    private val signer: AppSigner,
    private val inspector: ApkInspector,
    private val saveOutput: OutputSaver,
) : Tool {
    override val definition = DEFINITION

    /** One lock per project folder: two builds of one app would share build/tmp and take the same version. */
    private val projectLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val chat = context.workspace ?: return reply(messages.appNoWorkspace, error = true)
        val name = (arguments[PROJECT] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        // A confined chat's file tools reach only its project, so its builds do too: nothing of another project is read or run.
        if (projects.isConfined(chat)) {
            val own = projects.projectOf(chat)
            if (name != own) return reply(messages.appConfined(own.orEmpty()), error = true)
        }
        if (!ChatProjects.isValidName(name)) return reply(messages.appNoProject(name), error = true)
        val root = when (val folder = workspaces.folder()) {
            is Outcome.Failure -> return failed(folder.error.message)
            is Outcome.Success -> folder.value
        }
        val folder = File(root, name)
        return projectLocks.computeIfAbsent(name) { Mutex() }.withLock {
            toolchain.whileReady { build(chat, context, name, folder) }
                ?: ToolOutput("", isError = true, provenance = Provenance.Trusted, missingCapability = APP_BUILDING)
        }
    }

    private suspend fun build(chat: WorkspaceId, context: ToolExecutionContext, name: String, folder: File): ToolOutput {
        val project = withContext(Dispatchers.IO) { if (folder.isDirectory) AppProjects.read(folder) else null }
        if (project == null || project.packageName != config.packagePrefix + name) {
            return reply(messages.appNoProject(name), error = true)
        }
        val version = project.versionCode + 1
        val script = config.buildScript
            .replace("{{project}}", name).replace("{{slug}}", name).replace("{{versionCode}}", version.toString())
            .replace("{{minSdk}}", config.minSdk.toString()).replace("{{targetSdk}}", config.targetSdk.toString())
        val unsigned = File(folder, "build/$name-$version-unsigned.apk")
        val apk = File(folder, "build/$name-$version.apk")
        val report: ManifestReport
        try {
            val ran = when (val result = shell.run(script, network = false, timeoutSeconds = config.buildTimeoutSeconds)) {
                is Outcome.Failure -> return failed(result.error.message)
                is Outcome.Success -> result.value
            }
            if (ran.timedOut) return reply(messages.appBuildTimedOut(config.buildTimeoutSeconds), error = true)
            if (ran.exitCode != 0) return buildFailed(chat, context, ran)
            if (!unsigned.isFile) return reply(messages.appBuildNoApk, error = true)
            val signed = withContext(Dispatchers.IO) { signer.sign(unsigned, apk, project.packageName) }
            if (signed is Outcome.Failure) return failed(signed.error.message)
            // The signed APK is what gets installed, so it is what is checked.
            val facts = when (val inspected = withContext(Dispatchers.IO) { inspector.inspect(apk) }) {
                is Outcome.Failure -> {
                    apk.delete()
                    return failed(inspected.error.message)
                }
                is Outcome.Success -> inspected.value
            }
            report = AppManifest.classify(facts, config.powerful)
            refusal(facts, report, project, version)?.let { refused ->
                apk.delete()
                return reply(refused, error = true)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { unsigned.delete() }
        }
        try {
            withContext(Dispatchers.IO) {
                AppProjects.write(folder, project.copy(versionCode = version))
                pruneApks(File(folder, "build"), name)
            }
        } catch (e: IOException) {
            return failed(e.message ?: e.javaClass.simpleName)
        }
        val json = buildJsonObject {
            put("app", project.name)
            put("package", project.packageName)
            put("versionCode", version)
            put("apk", "$name/build/$name-$version.apk")
            put("sizeBytes", apk.length())
            put("permissions", JsonArray(report.permissions.map(::JsonPrimitive)))
            put("powerful", Json.encodeToJsonElement(ListSerializer(PowerfulDeclaration.serializer()), report.powerful))
        }
        return reply(json.toString())
    }

    /** Why the built APK can't be offered for install, or null when it can. */
    private fun refusal(facts: ApkFacts, report: ManifestReport, project: AppProject, version: Int): String? = when {
        report.sharedUserId -> messages.appSharedUserId
        report.packageName != project.packageName -> messages.appPackageMismatch(project.packageName)
        // aapt2 replaces a version the manifest sets, but only fills in SDKs it doesn't; either way, check what was built.
        facts.versionCode != version.toLong() -> messages.appVersionMismatch
        facts.minSdk != config.minSdk || facts.targetSdk != config.targetSdk -> messages.appSdkMismatch
        else -> null
    }

    /** Keeps the newest [NativeAppsConfig.keptApks] signed APKs in [build] and deletes every other APK there. */
    private fun pruneApks(build: File, name: String) {
        val signed = Regex("^${Regex.escape(name)}-(\\d+)\\.apk$")
        val apks = build.listFiles { file -> file.isFile && file.name.endsWith(".apk") }.orEmpty()
        val kept = apks.mapNotNull { file -> signed.find(file.name)?.groupValues?.get(1)?.toLongOrNull()?.let { file to it } }
            .sortedByDescending { it.second }.take(config.keptApks).map { it.first }.toSet()
        apks.filter { it !in kept }.forEach { it.delete() }
    }

    private suspend fun buildFailed(chat: WorkspaceId, context: ToolExecutionContext, ran: CommandResult): ToolOutput {
        val errors = BuildErrors.parse(ran.stderr + "\n" + ran.stdout, config.maxErrors, config.maxErrorChars)
        val full = ran.stderr + (if (ran.stdout.isNotBlank()) "\n" + ran.stdout else "")
        val saved = (saveOutput(chat, context.runId, context.toolCallId, full) as? Outcome.Success)?.value
        val text = messages.appBuildFailed(errors) + (saved?.let { "\n" + messages.appBuildLogSaved(it) } ?: "")
        return reply(text, error = true)
    }

    private fun failed(detail: String) = reply(messages.toolFailed(DEFINITION.id.value, detail), error = true)

    private fun reply(text: String, error: Boolean = false) = ToolOutput(text, isError = error, provenance = Provenance.Trusted)

    companion object {
        private const val PROJECT = "project"

        val DEFINITION = ToolDefinition(
            id = ToolId("build_app"),
            description = "Builds the Android app in a project folder of /workspace: compiles the Kotlin and resources in Linux " +
                "with no network, signs the APK and returns its path, size, permissions and any powerful capabilities it asks for. " +
                "On errors it returns the compiler errors to fix. Use it after editing an app made with create_app.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"project":{"type":"string","description":"The project's folder name in /workspace, as create_app gave it"}},"required":["project"]}""",
            ).jsonObject,
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_build_app",
            outputProvenance = Provenance.Trusted,
        )
    }
}
