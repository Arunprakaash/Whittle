package app.whittle

import android.content.Context
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
import app.whittle.design.theme.WhittleAppearance
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
import app.whittle.storage.runs.RoomRunCheckpointStore
import app.whittle.storage.secrets.EncryptedSecretStore
import app.whittle.storage.secrets.SecretStore
import app.whittle.storage.secrets.SecretStoreCredentialsProvider
import app.whittle.storage.secrets.TinkContentCipher
import app.whittle.tools.api.ToolRegistry
import app.whittle.tools.presentation.ToolPresentationConfig
import app.whittle.tools.presentation.ToolPresenter
import kotlinx.serialization.KSerializer
import okhttp3.OkHttpClient
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
    val providers = load(ConfigFiles.providerCatalog, ProviderCatalog.serializer(), ProviderCatalog.SCHEMA_VERSION)
    val modelDefaults = load(ConfigFiles.modelDefaults, ModelDefaults.serializer(), ModelDefaults.SCHEMA_VERSION)
    private val modelTrust = load(ConfigFiles.modelTrust, ModelTrustConfig.serializer(), ModelTrustConfig.SCHEMA_VERSION)

    val appearance = WhittleAppearance(
        wallpaperColors = appDefaults.appearance.wallpaperColors,
        haptics = appDefaults.appearance.haptics,
    )

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(harness.network.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(harness.network.readTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(harness.network.writeTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    private val cipher = TinkContentCipher(context, harness.secrets)
    val secrets: SecretStore = EncryptedSecretStore(context, harness.secrets, cipher)
    val connections = ConnectionStore(context, appDefaults.storage.connectionStoreName)
    val checkpoints = RoomRunCheckpointStore.open(context, appDefaults.storage.runsDatabaseName, cipher, clock)

    val modelLister = OpenAiCompatibleModelLister(httpClient, modelTrust, modelDefaults.fallbackCapabilities)
    val keyVerifier = ApiKeyVerifier(httpClient)
    val presenter = ToolPresenter(load(ConfigFiles.toolPresentation, ToolPresentationConfig.serializer(), ToolPresentationConfig.SCHEMA_VERSION))
    val inbox = RunInbox()

    val runner = AgentRunner(
        adapters = ModelAdapterRegistry(setOf(OpenAiCompatibleAdapter(httpClient, idGenerator), OpenAiResponsesAdapter(httpClient))),
        providers = providers,
        credentials = SecretStoreCredentialsProvider(secrets),
        tools = ToolRegistry(ToolCatalog.create(context, clock, httpClient)),
        policy = RuleBasedPolicyEngine(load(ConfigFiles.policyRules, PolicyRuleSet.serializer(), PolicyRuleSet.SCHEMA_VERSION)),
        inbox = inbox,
        checkpoints = checkpoints,
        telemetry = telemetry(context),
        limits = harness.agent,
        systemPrompt = PromptTemplate.load(configSource, ConfigFiles.agentSystemPrompt).orThrow(ConfigFiles.agentSystemPrompt),
        messages = load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION),
        clock = clock,
    )

    /** PostHog only when the build carries a project key; the user's consent level filters everything. */
    private fun telemetry(context: Context): Telemetry {
        val local = LocalTelemetry(idGenerator)
        if (BuildConfig.POSTHOG_PROJECT_KEY.isBlank()) return local
        PostHogAndroidClient.install(context, BuildConfig.POSTHOG_PROJECT_KEY, harness.telemetry)
        return ConsentFilteringTelemetry(
            delegate = PostHogTelemetry(PostHogAndroidClient(), idGenerator, clock),
            consent = { harness.telemetry.defaultConsent },
            maxAttributeChars = harness.telemetry.maxAttributeChars,
            fallback = local,
        )
    }

    private fun <T : VersionedConfig> load(name: ConfigName, serializer: KSerializer<T>, version: Int): T =
        configLoader.load(name, serializer, version).orThrow(name)

    private fun <T> Outcome<T>.orThrow(name: ConfigName): T = getOrElse { error("Bundled ${name.path} failed to load: ${it.message}") }
}
