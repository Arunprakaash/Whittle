package app.whittle.config.defaults

import app.whittle.agent.AgentMessages
import app.whittle.agent.SystemPromptVariables
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.config.HarnessConfig
import app.whittle.core.config.PromptTemplate
import app.whittle.llm.api.ModelDefaults
import app.whittle.llm.api.ModelTrustConfig
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.openrouter.OpenRouterAuthConfig
import app.whittle.policy.PolicyRuleSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Every bundled document must decode with its current schema, so a broken default never ships. */
class BundledConfigTest {
    private val source = BundledConfigSource()
    private val loader = ConfigLoader(source)

    @Test
    fun `harness config loads`() {
        assertIs<Outcome.Success<*>>(loader.load(ConfigFiles.harness, HarnessConfig.serializer(), HarnessConfig.SCHEMA_VERSION))
    }

    @Test
    fun `provider catalog loads`() {
        assertIs<Outcome.Success<*>>(
            loader.load(ConfigFiles.providerCatalog, ProviderCatalog.serializer(), ProviderCatalog.SCHEMA_VERSION),
        )
    }

    @Test
    fun `model trust config loads`() {
        assertIs<Outcome.Success<*>>(
            loader.load(ConfigFiles.modelTrust, ModelTrustConfig.serializer(), ModelTrustConfig.SCHEMA_VERSION),
        )
    }

    @Test
    fun `model defaults load`() {
        assertIs<Outcome.Success<*>>(loader.load(ConfigFiles.modelDefaults, ModelDefaults.serializer(), ModelDefaults.SCHEMA_VERSION))
    }

    @Test
    fun `policy rules load`() {
        assertIs<Outcome.Success<*>>(loader.load(ConfigFiles.policyRules, PolicyRuleSet.serializer(), PolicyRuleSet.SCHEMA_VERSION))
    }

    @Test
    fun `openrouter auth config loads`() {
        assertIs<Outcome.Success<*>>(
            loader.load(ConfigFiles.openRouterAuth, OpenRouterAuthConfig.serializer(), OpenRouterAuthConfig.SCHEMA_VERSION),
        )
    }

    @Test
    fun `agent messages load`() {
        assertIs<Outcome.Success<*>>(loader.load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION))
    }

    @Test
    fun `system prompt declares the expected placeholders`() {
        val template = PromptTemplate.load(source, ConfigFiles.agentSystemPrompt)
        assertIs<Outcome.Success<PromptTemplate>>(template)
        assertEquals(setOf(SystemPromptVariables.CURRENT_DATE, SystemPromptVariables.USER_LOCALE), template.value.placeholders)
    }

    @Test
    fun `wrong schema version is rejected`() {
        val result = loader.load(ConfigFiles.harness, HarnessConfig.serializer(), HarnessConfig.SCHEMA_VERSION + 1)
        assertIs<Outcome.Failure>(result)
    }
}
