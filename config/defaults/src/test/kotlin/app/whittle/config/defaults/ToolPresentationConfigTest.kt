package app.whittle.config.defaults

import app.whittle.core.common.EpochClock
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.device.DeviceClockTool
import app.whittle.tools.presentation.ToolPresentation
import app.whittle.tools.presentation.ToolPresentationConfig
import app.whittle.tools.presentation.ToolPresenter
import app.whittle.tools.web.WebFetchTool
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Keeps the bundled labels in step with the built-in tools, so no step row or card shows a raw id. */
class ToolPresentationConfigTest {
    private val config = ConfigLoader(BundledConfigSource())
        .load(ConfigFiles.toolPresentation, ToolPresentationConfig.serializer(), ToolPresentationConfig.SCHEMA_VERSION)
        .let { assertIs<Outcome.Success<ToolPresentationConfig>>(it).value }
    private val builtInTools: List<Tool> = listOf(DeviceClockTool(EpochClock.System), WebFetchTool(OkHttpClient()))

    @Test
    fun `fallback has a decision card`() {
        assertNotNull(config.fallback.decision)
    }

    @Test
    fun `every built-in tool has labels`() {
        val missing = builtInTools.map { it.definition.id.value } - config.tools.keys
        assertTrue(missing.isEmpty(), "No presentation for $missing")
    }

    @Test
    fun `every tool that can be asked about has a decision card`() {
        val missing = builtInTools.filter { it.definition.riskTier >= RiskTier.NETWORK_OUT && config.tools[it.definition.id.value]?.decision == null }
        assertTrue(missing.isEmpty(), "No decision card for ${missing.map { it.definition.id.value }}")
    }

    @Test
    fun `templates only use the tool's own arguments`() {
        builtInTools.forEach { tool ->
            val presentation = config.tools.getValue(tool.definition.id.value)
            val properties = tool.definition.inputSchema["properties"]?.jsonObject?.keys.orEmpty()
            val allowed = properties + properties.map { it + ToolPresenter.HOST_SUFFIX }
            val unknown = templates(presentation).flatMap { PromptTemplate(NAME, it).placeholders }.toSet() - allowed
            assertTrue(unknown.isEmpty(), "${tool.definition.id.value} uses unknown placeholders $unknown")
        }
    }

    private fun templates(presentation: ToolPresentation): List<String> =
        listOf(presentation.running, presentation.done) + listOfNotNull(presentation.decision).flatMap {
            listOf(it.title, it.body, it.approve, it.decline, it.approvedOutcome, it.declinedOutcome)
        }

    private companion object {
        val NAME = ConfigName("tool-presentation.json")
    }
}
