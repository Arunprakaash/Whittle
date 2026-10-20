package app.whittle

import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.design.icons.MaterialSymbols
import app.whittle.settings.AppDefaults
import app.whittle.tools.presentation.ToolPresentationConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
}
