package app.whittle

import app.whittle.runtimes.CommandSandbox
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
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrElse
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
import app.whittle.tools.workspace.PackageCdn
import app.whittle.tools.workspace.TerminalConfig
import app.whittle.tools.workspace.WebBuildConfig
import app.whittle.tools.workspace.WorkspaceToolSet
import app.whittle.tools.workspace.Workspaces
import kotlinx.serialization.KSerializer
import java.io.File
import app.whittle.artifacts.Artifacts
import app.whittle.runtimes.ToolRuntime
import app.whittle.web.SiteIconLoader
import app.whittle.webbuild.BuildRoutes
import app.whittle.webbuild.RemoteModules
import app.whittle.webbuild.WebViewBundler
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.IOException
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
    val providers = load(ConfigFiles.providerCatalog, ProviderCatalog.serializer(), ProviderCatalog.SCHEMA_VERSION)
    val modelDefaults = load(ConfigFiles.modelDefaults, ModelDefaults.serializer(), ModelDefaults.SCHEMA_VERSION)
    private val modelTrust = load(ConfigFiles.modelTrust, ModelTrustConfig.serializer(), ModelTrustConfig.SCHEMA_VERSION)

    /** Appearance before the user's saved settings have loaded. */
    val defaultAppearance = WhittleAppearance(
        wallpaperColors = appDefaults.appearance.wallpaperColors,
        haptics = appDefaults.appearance.haptics,
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

    /** One folder per chat for the terminal and file tools, in the app's private storage. */
    val workspaces = Workspaces(File(context.filesDir, appDefaults.storage.workspacesDirectoryName))

    /** Lives as long as the process: for writes that must finish even when a screen goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The user's settings, kept current; null until the saved choices have loaded. */
    val currentSettings: StateFlow<UserSettings?> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, null)

    val modelLister = OpenAiCompatibleModelLister(httpClient, modelTrust, modelDefaults.fallbackCapabilities)
    val keyVerifier = ApiKeyVerifier(httpClient)
    val artifacts = Artifacts(context, workspaces, appDefaults.artifacts)
    val artifactSheetHeight: Float get() = appDefaults.artifacts.sheetHeightFraction
    val siteIcons = SiteIconLoader(httpClient, File(context.cacheDir, appDefaults.siteIcons.cacheDirectoryName), appDefaults.siteIcons)
    val presenter = ToolPresenter(load(ConfigFiles.toolPresentation, ToolPresentationConfig.serializer(), ToolPresentationConfig.SCHEMA_VERSION))
    val inbox = RunInbox()

    val runner = AgentRunner(
        adapters = ModelAdapterRegistry(setOf(OpenAiCompatibleAdapter(httpClient, idGenerator), OpenAiResponsesAdapter(httpClient))),
        providers = providers,
        credentials = SecretStoreCredentialsProvider(secrets),
        tools = ToolRegistry(ToolCatalog.create(context, clock, httpClient, workspaceTools(context))),
        policy = RuleBasedPolicyEngine(load(ConfigFiles.policyRules, PolicyRuleSet.serializer(), PolicyRuleSet.SCHEMA_VERSION)),
        inbox = inbox,
        checkpoints = checkpoints,
        telemetry = telemetry(context),
        limits = harness.agent,
        systemPrompt = PromptTemplate.load(configSource, ConfigFiles.agentSystemPrompt).orThrow(ConfigFiles.agentSystemPrompt),
        messages = load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION),
        clock = clock,
    )

    /** The terminal and file tools: one folder per chat, in the app's private storage. */
    private fun workspaceTools(context: Context): List<Tool> {
        val terminal = appDefaults.terminal
        val environment = ShellEnvironment(
            shell = terminal.shell,
            path = terminal.path,
            launcher = CommandSandbox.launcher(context),
            killProcessGroup = { group ->
                try {
                    Os.kill(-group, OsConstants.SIGKILL)
                } catch (exception: ErrnoException) {
                    // Already gone; nothing left to stop.
                }
            },
            tools = ToolRuntime(context, appDefaults.toolRuntime, terminal.path, BuildConfig.RUNTIME_ASSETS),
        )
        return WorkspaceToolSet.create(
            workspaces = workspaces,
            environment = environment,
            config = load(TerminalConfig.FILE, TerminalConfig.serializer(), TerminalConfig.SCHEMA_VERSION),
            webBuild = webBuild(context),
        )
    }

    /** build_web_app: esbuild in a hidden WebView, with npm packages from the CDN cached on the phone. */
    private fun webBuild(context: Context): WorkspaceToolSet.WebBuild {
        val config = load(WebBuildConfig.FILE, WebBuildConfig.serializer(), WebBuildConfig.SCHEMA_VERSION)
        val cdn = PackageCdn(config.packages)
        val moduleClient = httpClient.newBuilder()
            .cache(Cache(File(context.cacheDir, config.host.cacheDirectoryName), config.host.cacheMaxBytes))
            .build()
        val routes = BuildRoutes(
            assets = { path ->
                try {
                    context.assets.open("${config.host.assetDirectory}/$path")
                } catch (exception: IOException) {
                    null
                }
            },
            cdn = cdn,
            remote = RemoteModules(moduleClient, config.packages.maxModuleBytes, cdn::allows),
        )
        return WorkspaceToolSet.WebBuild(
            bundler = WebViewBundler(context, config, routes, appScope),
            config = config,
            pageTemplate = PromptTemplate.load(configSource, WebBuildConfig.PAGE_TEMPLATE).orThrow(WebBuildConfig.PAGE_TEMPLATE),
        )
    }

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
}
