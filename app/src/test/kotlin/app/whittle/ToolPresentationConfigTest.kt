package app.whittle

import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.presentation.ToolPresentation
import app.whittle.tools.presentation.ToolPresentationConfig
import app.whittle.tools.presentation.ToolPresenter
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Keeps the bundled labels in step with the built-in tools, so no step row or card shows a raw id. */
class ToolPresentationConfigTest {
    private val config = ConfigLoader(BundledConfigSource())
        .load(ConfigFiles.toolPresentation, ToolPresentationConfig.serializer(), ToolPresentationConfig.SCHEMA_VERSION)
        .let { assertIs<Outcome.Success<ToolPresentationConfig>>(it).value }
    private val builtInTools: List<ToolDefinition> = ToolCatalog.definitions

    @Test
    fun `fallback has a decision card`() {
        assertNotNull(config.fallback.decision)
    }

    @Test
    fun `every built-in tool has labels`() {
        val missing = builtInTools.map { it.id.value } - config.tools.keys
        assertTrue(missing.isEmpty(), "No presentation for $missing")
    }

    @Test
    fun `every tool that can be asked about has a decision card`() {
        // Anything above reading can be asked about, depending on the model's trust tier and the rules.
        val missing = builtInTools.filter { it.riskTier > RiskTier.READ && config.tools[it.id.value]?.decision == null }
        assertTrue(missing.isEmpty(), "No decision card for ${missing.map { it.id.value }}")
    }

    @Test
    fun `templates only use the tool's own arguments`() {
        builtInTools.forEach { tool ->
            val presentation = config.tools.getValue(tool.id.value)
            val properties = tool.inputSchema["properties"]?.jsonObject?.keys.orEmpty()
            val allowed = properties + properties.map { it + ToolPresenter.HOST_SUFFIX } + properties.map { it + ToolPresenter.WHEN_SUFFIX }
            val unknown = templates(presentation).flatMap { PromptTemplate(NAME, it).placeholders }.toSet() - allowed
            assertTrue(unknown.isEmpty(), "${tool.id.value} uses unknown placeholders $unknown")
        }
    }

    @Test
    fun `question tools name arguments their schema has`() {
        builtInTools.forEach { tool ->
            val question = config.tools.getValue(tool.id.value).question ?: return@forEach
            val properties = tool.inputSchema["properties"]?.jsonObject?.keys.orEmpty()
            assertTrue(question.questionArgument in properties && question.optionsArgument in properties, "${tool.id.value} question arguments")
        }
    }

    @Test
    fun `every capability a tool needs has a card`() {
        val needed = builtInTools.flatMap { it.requiredCapabilities }.map { it.value }.toSet()
        val missing = needed - config.capabilities.keys
        assertTrue(missing.isEmpty(), "No capability card for $missing")
    }

    private fun templates(presentation: ToolPresentation): List<String> =
        listOf(presentation.running, presentation.done) + listOfNotNull(presentation.decision).flatMap {
            listOf(it.title, it.body, it.approve, it.decline, it.approvedOutcome, it.declinedOutcome)
        }

    private companion object {
        val NAME = ConfigName("tool-presentation.json")
    }
}
