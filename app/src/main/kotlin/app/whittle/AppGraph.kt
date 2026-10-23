package app.whittle

import app.whittle.phone.PhoneActionsConfig
import app.whittle.phone.PhoneTools
import app.whittle.reminders.AndroidReminderAlarms
import app.whittle.reminders.Reminder
import app.whittle.reminders.Reminders
import app.whittle.storage.EncryptedListFile
import app.whittle.background.RunNotifications
import app.whittle.agent.MemoryStore
import app.whittle.claudecode.ClaudeCode
import app.whittle.claudecode.ClaudeCodeConfig
import app.whittle.attachments.AttachmentImporter
import app.whittle.attachments.WorkspaceImages
import app.whittle.settings.AttachmentDefaults
import app.whittle.memory.EncryptedFileMemory
import app.whittle.tools.workspace.WorkspaceOutputs
import app.whittle.settings.MemoryIdea
import app.whittle.settings.VoiceDefaults
import app.whittle.connectors.CustomConnectorStore
import app.whittle.connectors.mcp.ConnectorsConfig
import app.whittle.connectors.Connectors
import app.whittle.apps.AndroidApkInspector
import app.whittle.apps.AndroidKeystoreKeys
import app.whittle.apps.AppInstaller
import app.whittle.apps.AppSigner
import app.whittle.apps.AppToolchain
import app.whittle.apps.BuildAppTool
import app.whittle.apps.CreateAppTool
import app.whittle.apps.LinuxShell
import app.whittle.apps.NativeAppsConfig
import app.whittle.linux.LinuxConfig
import app.whittle.linux.LinuxWorkspace
import app.whittle.design.theme.WhittleAppearance
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import app.whittle.core.config.ConsentLevel
import app.whittle.settings.UserSettings
import app.whittle.settings.SettingsStore
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.whittle.agent.AgentMessages
import app.whittle.agent.AgentRunner
import app.whittle.agent.RunInbox
import app.whittle.automations.AndroidAutomationAlarms
import app.whittle.automations.Automations
import app.whittle.automations.AutomationsConfig
import app.whittle.automations.EncryptedFileAutomations
import app.whittle.automations.ScheduleTaskTool
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrElse
import app.whittle.core.common.getOrNull
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.config.ConfigName
import app.whittle.core.config.HarnessConfig
import app.whittle.core.config.PromptTemplate
import app.whittle.core.config.VersionedConfig
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelDefaults
import app.whittle.llm.api.ModelTrustConfig
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.openai.ApiKeyVerifier
import app.whittle.llm.openai.OpenAiCompatibleAdapter
import app.whittle.llm.openai.OpenAiCompatibleModelLister
import app.whittle.llm.responses.OpenAiResponsesAdapter
import app.whittle.observability.ConsentFilteringTelemetry
import app.whittle.observability.LocalTelemetry
import app.whittle.observability.Telemetry
import app.whittle.observability.posthog.PostHogAndroidClient
import app.whittle.observability.posthog.PostHogTelemetry
import app.whittle.policy.PolicyRuleSet
import app.whittle.policy.RuleBasedPolicyEngine
import app.whittle.settings.AppDefaults
import app.whittle.settings.ConnectionStore
import app.whittle.storage.chats.ChatStore
import app.whittle.storage.chats.RoomChatStore
import app.whittle.storage.runs.RoomRunCheckpointStore
import app.whittle.storage.secrets.EncryptedSecretStore
import app.whittle.storage.secrets.SecretStore
import app.whittle.storage.secrets.SecretStoreCredentialsProvider
import app.whittle.storage.secrets.TinkContentCipher
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolRegistry
import app.whittle.tools.presentation.ToolPresentationConfig
import app.whittle.tools.presentation.ToolPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.whittle.tools.workspace.ShellEnvironment
import app.whittle.tools.workspace.ProcessManager
import app.whittle.tools.workspace.CommandRunner
import app.whittle.tools.workspace.TerminalConfig
import app.whittle.tools.workspace.WorkspaceToolSet
import app.whittle.files.ChatProjectStore
import app.whittle.files.FileChangeLog
import app.whittle.settings.ProjectDefaults
import app.whittle.settings.FilesDefaults
import app.whittle.tools.workspace.Workspaces
import kotlinx.serialization.KSerializer
import java.io.File
import app.whittle.runtimes.ToolRuntime
import app.whittle.web.SiteIconLoader
import okhttp3.OkHttpClient
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Everything the app is built from, created once per process. Bundled config is checked by tests,
 * so a document that fails to load here is a build bug and stops the app at launch.
 */
class AppGraph(context: Context) {
    private val configSource = BundledConfigSource()
    private val configLoader = ConfigLoader(configSource)

    val clock: EpochClock = EpochClock.System
    val idGenerator: IdGenerator = IdGenerator.Random

    private val harness = load(ConfigFiles.harness, HarnessConfig.serializer(), HarnessConfig.SCHEMA_VERSION)
    private val appDefaults = load(AppDefaults.FILE, AppDefaults.serializer(), AppDefaults.SCHEMA_VERSION)
    val sidebarMaxChats: Int get() = appDefaults.chatList.sidebarMaxChats
    val memoryIdeas: List<MemoryIdea> get() = appDefaults.memory.ideas
    val providers = load(ConfigFiles.providerCatalog, ProviderCatalog.serializer(), ProviderCatalog.SCHEMA_VERSION)
    val modelDefaults = load(ConfigFiles.modelDefaults, ModelDefaults.serializer(), ModelDefaults.SCHEMA_VERSION)
    private val modelTrust = load(ConfigFiles.modelTrust, ModelTrustConfig.serializer(), ModelTrustConfig.SCHEMA_VERSION)

    /** Appearance before the user's saved settings have loaded. */
    val defaultAppearance = WhittleAppearance(
        wallpaperColors = appDefaults.appearance.wallpaperColors,
        haptics = appDefaults.appearance.haptics,
        themeMode = appDefaults.appearance.theme,
    )

    /** Whether this build can send analytics at all; without a project key everything stays on the phone. */
    val analyticsAvailable: Boolean = BuildConfig.POSTHOG_PROJECT_KEY.isNotBlank()

    /** This build records screens unmasked, for its developer's own testing. Settings says so. */
    val recordingsShowContent: Boolean = BuildConfig.REPLAY_SHOW_CONTENT

    val settings = SettingsStore(
        context,
        appDefaults.storage.settingsStoreName,
        UserSettings(
            wallpaperColors = appDefaults.appearance.wallpaperColors,
            haptics = appDefaults.appearance.haptics,
            shareUsageData = harness.telemetry.defaultConsent != ConsentLevel.OFF,
            screenRecordings = harness.telemetry.sessionReplay.enabled,
            keepRunningInBackground = appDefaults.background.keepRunning,
            themeMode = appDefaults.appearance.theme,
        ),
    )

    /** Whether tasks keep running in the background before the user's saved choice has loaded. */
    val defaultKeepRunningInBackground: Boolean get() = appDefaults.background.keepRunning

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(harness.network.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(harness.network.readTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(harness.network.writeTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    private val cipher = TinkContentCipher(context, harness.secrets)
    val secrets: SecretStore = EncryptedSecretStore(context, harness.secrets, cipher)
    val connections = ConnectionStore(context, appDefaults.storage.connectionStoreName)
    val checkpoints = RoomRunCheckpointStore.open(context, appDefaults.storage.runsDatabaseName, cipher, clock)
    val chats: ChatStore = RoomChatStore.open(context, appDefaults.storage.chatsDatabaseName, cipher, clock)

    private val terminalLimits = load(TerminalConfig.FILE, TerminalConfig.serializer(), TerminalConfig.SCHEMA_VERSION)

    /** The Linux every command runs in (docs/linux-workspace.md). */
    val linux = LinuxWorkspace(
        context = context,
        config = load(LinuxConfig.FILE, LinuxConfig.serializer(), LinuxConfig.SCHEMA_VERSION),
        programs = ToolRuntime(context, appDefaults.toolRuntime, appDefaults.terminal.path, BuildConfig.RUNTIME_ASSETS),
        http = httpClient,
        terminal = appDefaults.terminal,
        killProcessGroup = { group ->
            try {
                Os.kill(-group, OsConstants.SIGKILL)
            } catch (exception: ErrnoException) {
                // Already gone; nothing left to stop.
            }
        },
        maxOutputChars = terminalLimits.maxOutputChars,
    )

    /** Servers and watchers the agent starts; they run in Linux until stopped, or until the app ends. */
    val processes by lazy { ProcessManager(linux.environment, terminalLimits.processes, appScope) }

    /** The workspace every chat shares, in the app's private storage, mounted into Linux as /workspace. */
    /** Lives as long as the process: for writes that must finish even when a screen goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The project each chat works on; commands and relative paths follow it. */
    val chatProjects = ChatProjectStore(File(context.filesDir, appDefaults.storage.chatProjectsFileName), appScope)

    val workspaces = Workspaces(File(context.filesDir, appDefaults.storage.workspaceDirectoryName), linux.fileSystem, chatProjects)

    /** How apps are built (docs/native-apps.md). */
    val nativeApps = load(NativeAppsConfig.FILE, NativeAppsConfig.serializer(), NativeAppsConfig.SCHEMA_VERSION)

    private val appShell = run {
        val runner = CommandRunner(linux.environment, terminalLimits.maxOutputChars)
        LinuxShell { command, network, timeoutSeconds ->
            when (val folder = workspaces.folder()) {
                is Outcome.Failure -> folder
                is Outcome.Success -> runner.run(command, folder.value, timeoutSeconds, network)
            }
        }
    }

    /** The app-building toolchain in Linux; one instance, shared by its service, the chat and Settings. */
    val appToolchain: AppToolchain = AppToolchain(appShell, nativeApps)

    /** The one signer and APK inspector: the build tools and the install card share them. */
    private val appSigner = AppSigner(AndroidKeystoreKeys())
    private val apkInspector = AndroidApkInspector(context.packageManager)

    /** Installs, updates and reads the state of the apps the agent built; only the user's tap calls it. */
    val appInstaller = AppInstaller(context, appSigner, apkInspector, nativeApps.powerful)

    /** The agent's `create_app` and `build_app`; the package is Whittle's own, so a debug build gives app.whittle.debug. */
    private fun appTools(context: Context): Pair<CreateAppTool, BuildAppTool> = CreateAppTool(
        workspaces, chatProjects, nativeApps, context.packageName, agentMessages,
        { packageName -> runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess },
        { path -> (configSource.read(ConfigName("native-app-template/$path")) as Outcome.Success).value },
    ) to BuildAppTool(
        workspaces, nativeApps, agentMessages, appToolchain::isReady, appShell,
        appSigner, apkInspector,
        WorkspaceOutputs(workspaces, harness.agent.toolOutput.directory)::save,
    )

    /** How a chat's project is put to the agent, and how many recent ones a new chat offers. */
    val projectDefaults: ProjectDefaults get() = appDefaults.projects

    /** The file browser's settings. */
    val filesDefaults: FilesDefaults get() = appDefaults.files

    val attachmentDefaults: AttachmentDefaults get() = appDefaults.attachments

    /** Photos and files the user adds to a message, copied into the workspace. */
    val attachments = AttachmentImporter(context.contentResolver, workspaces, appDefaults.attachments)

    /** Attached photos, for the model and for the screen. */
    val images = WorkspaceImages(workspaces, appDefaults.attachments)

    /** What each chat's agent changed in the workspace, for the file browser's Changes. */
    val fileChanges = FileChangeLog(File(context.filesDir, appDefaults.files.changesDirectoryName), workspaces)

    /** The user's settings, kept current; null until the saved choices have loaded. */
    val currentSettings: StateFlow<UserSettings?> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, null)

    init {
        appScope.launch { settings.ensureAvatarSeed() }
        appScope.launch(Dispatchers.IO) { appInstaller.cleanUp() }
    }

    val modelLister = OpenAiCompatibleModelLister(httpClient, modelTrust, modelDefaults.fallbackCapabilities)
    val keyVerifier = ApiKeyVerifier(httpClient)
    /** Services the user connects with their own account; their tools join the agent's once signed in. */
    val connectors = Connectors(
        secrets = secrets,
        config = load(ConnectorsConfig.FILE, ConnectorsConfig.serializer(), ConnectorsConfig.SCHEMA_VERSION),
        http = httpClient,
        clock = clock,
        scope = appScope,
        directory = File(context.filesDir, appDefaults.storage.connectorsDirectoryName),
        clientVersion = BuildConfig.VERSION_NAME,
        custom = CustomConnectorStore(context, appDefaults.storage.customConnectorsStoreName),
        loopbackPage = PromptTemplate.load(configSource, ConfigFiles.loopbackSignInPage).orThrow(ConfigFiles.loopbackSignInPage),
    )
    val voiceDefaults: VoiceDefaults get() = appDefaults.voice
    val previewSheetHeight: Float get() = appDefaults.preview.sheetHeightFraction
    val siteIcons = SiteIconLoader(httpClient, File(context.cacheDir, appDefaults.siteIcons.cacheDirectoryName), appDefaults.siteIcons)
    val presenter = ToolPresenter(load(ConfigFiles.toolPresentation, ToolPresentationConfig.serializer(), ToolPresentationConfig.SCHEMA_VERSION))
    val inbox = RunInbox()

    val agentMessages = load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION)

    /** What Whittle remembers about the user: the harness's memory, kept encrypted on the phone. */
    val memory = MemoryStore(
        EncryptedFileMemory(File(context.filesDir, appDefaults.memory.fileName), cipher),
        harness.agent.memory,
        agentMessages,
        clock,
        idGenerator,
        appScope,
    )

    /** Tasks Whittle runs by itself on a schedule, each with an alarm for its next run. */
    val automations = Automations(
        backend = EncryptedFileAutomations(File(context.filesDir, appDefaults.storage.automationsFileName), cipher),
        alarms = AndroidAutomationAlarms(context),
        config = load(AutomationsConfig.FILE, AutomationsConfig.serializer(), AutomationsConfig.SCHEMA_VERSION),
        clock = clock,
        ids = idGenerator,
        zones = ZoneId::systemDefault,
        scope = appScope,
    )

    /** The phone tools' settings: where maps open, reminders and sharing. */
    val phoneActions = load(PhoneActionsConfig.FILE, PhoneActionsConfig.serializer(), PhoneActionsConfig.SCHEMA_VERSION)

    /** Whittle's own reminders, each a notification at its time. */
    val reminders = Reminders(
        store = EncryptedListFile(File(context.filesDir, phoneActions.reminders.fileName), cipher, Reminder.serializer(), REMINDERS_DATA, REMINDERS_LABEL),
        alarms = AndroidReminderAlarms(context),
        clock = clock,
        ids = idGenerator,
        maxReminders = phoneActions.reminders.maxReminders,
        maxTextChars = phoneActions.reminders.maxTextChars,
        show = { reminder -> RunNotifications(context).reminder(reminder.id, reminder.text) },
    )

    /** Every tool a task can use: the built-in ones and those of the user's connections. */
    val tools = appTools(context).let { (createApp, buildApp) ->
        ToolRegistry(
            ToolCatalog.create(
                context, clock, httpClient, workspaceTools(), harness.agent.maxPlanItems, connectors, memory, agentMessages, scheduleTask(),
                PhoneTools(phoneActions, reminders, workspaces) { id -> currentSettings.value?.phoneOff?.contains(id) != true },
                createApp,
                buildApp,
            ),
            connectors::tools,
        )
    }

    /** Claude Code, run in Linux with the user's own Claude plan, as another way to bring a model. */
    val claudeCode = ClaudeCodeConfig.FILE.let { file ->
        val config = load(file, ClaudeCodeConfig.serializer(), ClaudeCodeConfig.SCHEMA_VERSION)
        ClaudeCode(
            config = config,
            environment = linux.environment,
            workspaces = workspaces,
            scope = appScope,
            sessionsFile = File(context.filesDir, config.sessionsFileName),
            updatedStamp = File(context.filesDir, config.updatedStampFileName),
            inbox = inbox,
            changes = fileChanges,
            maxFileChars = terminalLimits.maxFileChars,
            connections = connectors::access,
            linuxFile = { path -> linux.root()?.let { File(it, path.removePrefix("/")) } },
            secrets = secrets,
            http = httpClient,
        )
    }

    val runner = AgentRunner(
        adapters = ModelAdapterRegistry(setOf(OpenAiCompatibleAdapter(httpClient, idGenerator), OpenAiResponsesAdapter(httpClient))),
        providers = providers,
        credentials = SecretStoreCredentialsProvider(secrets),
        tools = tools,
        policy = RuleBasedPolicyEngine(load(ConfigFiles.policyRules, PolicyRuleSet.serializer(), PolicyRuleSet.SCHEMA_VERSION)),
        inbox = inbox,
        checkpoints = checkpoints,
        telemetry = telemetry(context),
        limits = harness.agent,
        systemPrompt = PromptTemplate.load(configSource, ConfigFiles.agentSystemPrompt).orThrow(ConfigFiles.agentSystemPrompt),
        messages = agentMessages,
        clock = clock,
        outputs = WorkspaceOutputs(workspaces, harness.agent.toolOutput.directory)::save,
        memory = memory,
        images = images,
    )

    /** schedule_task: an automation takes the chat's project unless the model names another that exists. */
    private fun scheduleTask() = ScheduleTaskTool(
        automations = automations,
        messages = agentMessages,
        projectOf = workspaces::projectOf,
        projectExists = { name -> workspaces.folder().getOrNull()?.let { File(it, name).isDirectory } == true },
    )

    /** The terminal and file tools, working in the shared workspace inside Linux. */
    private fun workspaceTools(): List<Tool> = WorkspaceToolSet.create(
        workspaces = workspaces,
        environment = linux.environment,
        config = terminalLimits,
        processes = processes,
        changes = fileChanges,
        projects = chatProjects,
    )

    /**
     * PostHog only when the build carries a project key. The user's Settings decide what leaves the phone:
     * nothing until their choices load, then usage data at the configured level, recordings only if allowed.
     */
    private fun telemetry(context: Context): Telemetry {
        val local = LocalTelemetry(idGenerator)
        if (!analyticsAvailable) return local
        PostHogAndroidClient.install(context, BuildConfig.POSTHOG_PROJECT_KEY, harness.telemetry, showContent = recordingsShowContent, debugLogs = BuildConfig.POSTHOG_DEBUG)
        appScope.launch {
            currentSettings.filterNotNull().map { it.shareUsageData to it.screenRecordings }.distinctUntilChanged().collect { (share, record) ->
                PostHogAndroidClient.applyChoices(shareUsageData = share, screenRecordings = record)
            }
        }
        val sharingLevel = harness.telemetry.defaultConsent.takeUnless { it == ConsentLevel.OFF } ?: ConsentLevel.METADATA
        return ConsentFilteringTelemetry(
            delegate = PostHogTelemetry(PostHogAndroidClient(), idGenerator, clock),
            consent = { if (currentSettings.value?.shareUsageData == true) sharingLevel else ConsentLevel.OFF },
            maxAttributeChars = harness.telemetry.maxAttributeChars,
            fallback = local,
        )
    }

    private fun <T : VersionedConfig> load(name: ConfigName, serializer: KSerializer<T>, version: Int): T =
        configLoader.load(name, serializer, version).orThrow(name)

    private fun <T> Outcome<T>.orThrow(name: ConfigName): T = getOrElse { error("Bundled ${name.path} failed to load: ${it.message}") }

    private companion object {
        val REMINDERS_DATA = "whittle-reminders".encodeToByteArray()
        const val REMINDERS_LABEL = "reminders"
    }
}
