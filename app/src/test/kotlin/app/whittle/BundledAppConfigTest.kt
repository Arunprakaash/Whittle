package app.whittle

import app.whittle.apps.NativeAppsConfig
import app.whittle.automations.AutomationsConfig
import app.whittle.claudecode.ClaudeCodeConfig
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.llm.api.HostedTool
import app.whittle.tools.api.RiskTier
import app.whittle.connectors.matches
import app.whittle.connectors.mcp.ConnectorsConfig
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigName
import app.whittle.core.config.ConfigLoader
import app.whittle.design.icons.MaterialSymbols
import app.whittle.linux.LinuxBaseSetup
import app.whittle.linux.LinuxConfig
import app.whittle.phone.PhoneActionsConfig
import app.whittle.tools.android.AndroidCapabilities
import app.whittle.tools.api.CapabilityId
import app.whittle.settings.AppDefaults
import app.whittle.tools.presentation.ToolPresentationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `memory ideas have bundled icons and somewhere to start`() {
        val defaults = assertIs<Outcome.Success<AppDefaults>>(loader.load(AppDefaults.FILE, AppDefaults.serializer(), AppDefaults.SCHEMA_VERSION)).value
        val ideas = defaults.memory.ideas
        assertTrue(ideas.isNotEmpty())
        val missingIcons = ideas.map { it.icon }.toSet() - MaterialSymbols.names
        assertTrue(missingIcons.isEmpty(), "Add these to design/system/icons/icons.txt: $missingIcons")
        assertTrue(ideas.all { it.title.isNotBlank() && it.start.isNotBlank() })
    }

    @Test
    fun `attachments go to a plain uploads folder, and every file icon is bundled`() {
        val defaults = assertIs<Outcome.Success<AppDefaults>>(loader.load(AppDefaults.FILE, AppDefaults.serializer(), AppDefaults.SCHEMA_VERSION)).value
        assertEquals("uploads", defaults.attachments.directoryName)
        assertTrue(defaults.files.isPdf("receipts.PDF"))
        val missingIcons = defaults.files.fileIcons.values.toSet() + defaults.files.defaultFileIcon + defaults.files.folderIcon - MaterialSymbols.names
        assertTrue(missingIcons.isEmpty(), "Add these to design/system/icons/icons.txt: $missingIcons")
    }

    @Test
    fun `Claude Code installs from Anthropic's repository and offers its models by alias`() {
        val config = assertIs<Outcome.Success<ClaudeCodeConfig>>(loader.load(ClaudeCodeConfig.FILE, ClaudeCodeConfig.serializer(), ClaudeCodeConfig.SCHEMA_VERSION)).value
        assertTrue("https://downloads.claude.ai/" in config.installCommand)
        assertTrue(config.models.map { it.id }.containsAll(listOf("opus", "sonnet", "haiku")))
        assertTrue("--permission-prompt-tool" in config.runCommand && "stream-json" in config.runCommand)
        // Every sign-in Claude Code offers is offered here too, and their icons are bundled.
        assertEquals(setOf("--claudeai", "--console"), config.signInMethods.map { it.arguments }.toSet())
        val missingIcons = (config.signInMethods.map { it.icon } + config.apiKey.icon).toSet() - MaterialSymbols.names
        assertTrue(missingIcons.isEmpty(), "Add these to design/system/icons/icons.txt: $missingIcons")
        assertTrue(config.apiKey.checkUrl.startsWith("https://"))
        // Updates come from Claude Code's own list only, so nothing else in Linux changes under the user.
        assertTrue("claude-code.list" in config.updateCommand && "--only-upgrade claude-code" in config.updateCommand)
        // An automation can't let Claude Code run what it couldn't let Whittle's own tools run.
        val automations = assertIs<Outcome.Success<AutomationsConfig>>(
            loader.load(AutomationsConfig.FILE, AutomationsConfig.serializer(), AutomationsConfig.SCHEMA_VERSION),
        ).value
        val unchoosable = config.preApprovedTools.keys - automations.choosableToolIds
        assertTrue(unchoosable.isEmpty(), "claude-code.json maps tools an automation can't pre-approve: $unchoosable")
    }

    @Test
    fun `phone actions load with maps links that take their places`() {
        val phone = assertIs<Outcome.Success<PhoneActionsConfig>>(loader.load(PhoneActionsConfig.FILE, PhoneActionsConfig.serializer(), PhoneActionsConfig.SCHEMA_VERSION)).value
        assertTrue(phone.maps.directionsUri.startsWith("https://"))
        assertTrue(phone.reminders.maxReminders > 0)
        // Every switch stops tools that exist, asks for capabilities Android knows, and has a bundled icon.
        val toolIds = ToolCatalog.definitions.map { it.id.value }.toSet()
        val unknownTools = phone.switches.flatMap { it.tools } - toolIds
        assertTrue(unknownTools.isEmpty(), "phone-actions.json switches name tools that don't exist: $unknownTools")
        val unknownCapabilities = phone.switches.flatMap { it.capabilities }.filter { AndroidCapabilities.permissionFor(CapabilityId(it)) == null }
        assertTrue(unknownCapabilities.isEmpty(), "Capabilities without an Android permission: $unknownCapabilities")
        val missingIcons = phone.switches.map { it.icon }.toSet() - MaterialSymbols.names
        assertTrue(missingIcons.isEmpty(), "Add these to design/system/icons/icons.txt: $missingIcons")
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
    fun `base setup installs its packages, PDF tools among them, and a top-up installs only the missing ones`() {
        val setup = assertIs<Outcome.Success<LinuxConfig>>(loader.load(LinuxConfig.FILE, LinuxConfig.serializer(), LinuxConfig.SCHEMA_VERSION)).value.baseSetup
        // pdftotext and pdfinfo, so the agent can read PDF attachments.
        assertTrue("poppler-utils" in setup.packages)
        val all = setup.install(setup.packages)
        assertTrue(setup.packages.all { it in all.split(" ") }, all)
        assertFalse("{{" in all)
        val topUp = setup.install(listOf("poppler-utils"))
        assertTrue("poppler-utils" in topUp && "xz-utils" !in topUp, topUp)
    }

    @Test
    fun `base setup refuses package names a shell would read as more than a name`() {
        assertFailsWith<IllegalArgumentException> {
            LinuxBaseSetup(packages = listOf("git; rm -rf /"), installCommand = "apt-get install {{packages}}", commands = emptyList(), timeoutSeconds = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            LinuxBaseSetup(packages = listOf("git"), installCommand = "apt-get install git", commands = emptyList(), timeoutSeconds = 1)
        }
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
    fun `automation ideas only read, and their icons are bundled`() {
        val config = assertIs<Outcome.Success<AutomationsConfig>>(
            loader.load(AutomationsConfig.FILE, AutomationsConfig.serializer(), AutomationsConfig.SCHEMA_VERSION),
        ).value
        assertTrue(config.ideas.isNotEmpty())
        val tiers = ToolCatalog.definitions.associate { it.id.value to it.riskTier } + (HostedTool.WEB_SEARCH.toolName to RiskTier.WEB_READ)
        val readOnly = setOf(RiskTier.READ, RiskTier.WEB_READ)
        // An idea is one tap from saved, so like a new automation it never starts able to change anything.
        val changing = config.ideas.associate { it.name to config.toolIdsFor(it).filter { id -> tiers.getValue(id) !in readOnly } }.filterValues { it.isNotEmpty() }
        assertTrue(changing.isEmpty(), "These ideas would change things without asking: $changing")
        val missingIcons = config.ideas.map { it.icon }.toSet() - MaterialSymbols.names
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

    @Test
    fun `connections Whittle comes with are listed in a library group, with a line about them`() {
        val config = assertIs<Outcome.Success<ConnectorsConfig>>(
            loader.load(ConnectorsConfig.FILE, ConnectorsConfig.serializer(), ConnectorsConfig.SCHEMA_VERSION),
        ).value
        val groups = config.library.categories.map { it.id }.toSet()
        config.servers.forEach { server ->
            assertTrue(server.category in groups, "${server.name} needs a library category, or it shows nowhere until connected")
            assertFalse(server.description.isNullOrBlank(), "${server.name} needs a description for the library")
        }
    }

    @Test
    fun `native apps config loads with pinned downloads and a powerful list`() {
        val config = assertIs<Outcome.Success<NativeAppsConfig>>(
            loader.load(NativeAppsConfig.FILE, NativeAppsConfig.serializer(), NativeAppsConfig.SCHEMA_VERSION),
        ).value
        val toolchain = config.toolchain
        toolchain.downloads.forEach {
            assertTrue(Regex("^[0-9a-f]{64}$").matches(it.sha256), "${it.url} must be pinned by its SHA-256")
            assertTrue(it.url.startsWith("https://"))
            val file = it.saveAs.substringAfterLast('/')
            assertTrue(it.saveAs.startsWith("/opt/whittle-apps/downloads/"), "the script saves downloads in that folder")
            assertTrue("curl -fsSL -o $file ${it.url}\n" in toolchain.setupScript, "the setup script must fetch ${it.url} as $file")
            assertTrue("'${it.sha256}  $file'" in toolchain.setupScript, "the setup script must check $file against its pin")
        }
        val aptLine = toolchain.setupScript.lines().single { it.startsWith("apt-get install") }
        toolchain.aptPackages.forEach { assertTrue(Regex("(^| )${Regex.escape(it)}( |$)").containsMatchIn(aptLine), "apt line lacks $it") }
        assertEquals(toolchain.aptPackages.size, aptLine.split(" ").count { !it.startsWith("-") } - 2)
        val placeholder = Regex("\\{\\{(\\w+)}}")
        val buildAllowed = setOf("project", "slug", "versionCode", "minSdk", "targetSdk")
        assertTrue(placeholder.findAll(config.buildScript).map { it.groupValues[1] }.toSet().all { it in buildAllowed })
        assertEquals(setOf("pinsHash"), placeholder.findAll(toolchain.setupScript).map { it.groupValues[1] }.toSet())
        assertTrue(config.palette.size >= 4)
        val named = config.powerful.map { it.name }
        listOf("BIND_ACCESSIBILITY_SERVICE", "BIND_NOTIFICATION_LISTENER_SERVICE", "SYSTEM_ALERT_WINDOW", "READ_SMS").forEach {
            assertTrue("android.permission.$it" in named, it)
        }
        val source = BundledConfigSource()
        config.template.forEach { path ->
            assertIs<Outcome.Success<String>>(source.read(ConfigName("native-app-template/$path")), path)
        }
    }
}
