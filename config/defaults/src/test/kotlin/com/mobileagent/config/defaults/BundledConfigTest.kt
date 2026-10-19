package com.mobileagent.config.defaults

import com.mobileagent.agent.AgentMessages
import com.mobileagent.core.common.Outcome
import com.mobileagent.core.config.ConfigFiles
import com.mobileagent.core.config.ConfigLoader
import com.mobileagent.core.config.HarnessConfig
import com.mobileagent.core.config.PromptTemplate
import com.mobileagent.llm.api.ModelDefaults
import com.mobileagent.llm.api.ModelTrustConfig
import com.mobileagent.llm.api.ProviderCatalog
import com.mobileagent.llm.openrouter.OpenRouterAuthConfig
import com.mobileagent.policy.PolicyRuleSet
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
        assertEquals(setOf("currentDate", "userLocale"), template.value.placeholders)
    }

    @Test
    fun `wrong schema version is rejected`() {
        val result = loader.load(ConfigFiles.harness, HarnessConfig.serializer(), HarnessConfig.SCHEMA_VERSION + 1)
        assertIs<Outcome.Failure>(result)
    }
}
