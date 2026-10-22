package app.whittle

import app.whittle.automations.AutomationsConfig
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.llm.api.HostedTool
import app.whittle.tools.api.RiskTier
import app.whittle.connectors.matches
import app.whittle.connectors.mcp.ConnectorsConfig
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.design.icons.MaterialSymbols
import app.whittle.linux.LinuxConfig
import app.whittle.settings.AppDefaults
import app.whittle.tools.presentation.ToolPresentationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledAppConfigTest {
    private val loader = ConfigLoader(BundledConfigSource())

    @Test
    fun `app defaults load with wallpaper colors off and haptics on`() {
        val defaults = assertIs<Outcome.Success<AppDefaults>>(loader.load(AppDefaults.FILE, AppDefaults.serializer(), AppDefaults.SCHEMA_VERSION)).value
        assertFalse(defaults.appearance.wallpaperColors)
        assertTrue(defaults.appearance.haptics)
    }

    @Test
    fun `the Linux config loads with a checked image for arm64`() {
        val linux = assertIs<Outcome.Success<LinuxConfig>>(loader.load(LinuxConfig.FILE, LinuxConfig.serializer(), LinuxConfig.SCHEMA_VERSION)).value
        val image = assertNotNull(linux.images["arm64-v8a"])
        assertTrue(Regex("^[0-9a-f]{64}$").matches(image.sha256), "the image must be pinned by its SHA-256")
        assertTrue(image.url.startsWith("https://"))
        assertTrue(linux.workspaceMount !in linux.deviceMounts)
    }

    @Test
    fun `automations can only pre-approve built-in tools that read, never ones that send data out`() {
        val config = assertIs<Outcome.Success<AutomationsConfig>>(
            loader.load(AutomationsConfig.FILE, AutomationsConfig.serializer(), AutomationsConfig.SCHEMA_VERSION),
        ).value
        // Hosted web search is a web read the provider runs (see HostedToolDefinitions).
        val tiers = ToolCatalog.definitions.associate { it.id.value to it.riskTier } + (HostedTool.WEB_SEARCH.toolName to RiskTier.WEB_READ)
        val named = config.choosableToolIds + config.alwaysPreApproved
        val unknown = named - tiers.keys
        assertTrue(unknown.isEmpty(), "automations.json names tools that don't exist: $unknown")
        val risky = named.filter { tiers.getValue(it) > RiskTier.WEB_READ }
        assertTrue(risky.isEmpty(), "Automations must not run these without asking: $risky")
        assertTrue(config.defaultToolIds.isNotEmpty())
        // Reading is all a new automation may do: anything that changes files is the user's to turn on.
        val readOnly = setOf(RiskTier.READ, RiskTier.WEB_READ)
        val changing = (config.defaultToolIds + config.alwaysPreApproved).filter { tiers.getValue(it) !in readOnly }
        assertTrue(changing.isEmpty(), "New automations must not change anything without asking: $changing")
        // A choice that changes files says where: the whole workspace without a project, only the project with one.
        val unlabelled = config.tools.filter { choice -> choice.toolIds.any { tiers.getValue(it) !in readOnly } && choice.labelInProject == null }
        assertTrue(unlabelled.isEmpty(), "Give these a labelInProject: ${unlabelled.map { it.key }}")
        val missingIcons = config.tools.map { it.icon }.toSet() - MaterialSymbols.names
        assertTrue(missingIcons.isEmpty(), "Add these to design/system/icons/icons.txt: $missingIcons")
    }

    @Test
    fun `every icon the tool labels name is bundled`() {
        val config = assertIs<Outcome.Success<ToolPresentationConfig>>(
            loader.load(ConfigFiles.toolPresentation, ToolPresentationConfig.serializer(), ToolPresentationConfig.SCHEMA_VERSION),
        ).value
        val presentations = config.tools.values + config.fallback
        val named = presentations.flatMap { listOfNotNull(it.icon, it.decision?.icon, it.decision?.approveIcon, it.question?.icon) }.toSet() +
            config.capabilities.values.map { it.icon }
        val missing = named - MaterialSymbols.names
        assertTrue(missing.isEmpty(), "Add these to design/system/icons/icons.txt: $missing")
    }

    @Test
    fun `the connection library is grouped, every group has servers, and people find hubs by the apps they reach`() {
        val library = assertIs<Outcome.Success<ConnectorsConfig>>(
            loader.load(ConnectorsConfig.FILE, ConnectorsConfig.serializer(), ConnectorsConfig.SCHEMA_VERSION),
        ).value.library
        library.categories.forEach { category -> assertTrue(library.servers.any { it.category == category.id }, "${category.title} is empty") }
        assertTrue(library.servers.all { it.url.startsWith("https://") })
        listOf("gmail", "google calendar", "excel", "outlook", "google docs").forEach { app ->
            assertEquals(listOf("Zapier"), library.servers.filter { it.matches(app) }.map { it.title }, app)
        }
    }
}
