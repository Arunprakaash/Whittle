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
        assertEquals(
            setOf(
                SystemPromptVariables.CURRENT_DATE, SystemPromptVariables.USER_LOCALE, SystemPromptVariables.USER_TIME_ZONE,
                SystemPromptVariables.PROJECT, SystemPromptVariables.MEMORIES,
            ),
            template.value.placeholders,
        )
    }

    @Test
    fun `models that can't chat with tools never reach the picker`() {
        val catalog = assertIs<Outcome.Success<ProviderCatalog>>(
            loader.load(ConfigFiles.providerCatalog, ProviderCatalog.serializer(), ProviderCatalog.SCHEMA_VERSION),
        ).value
        val openai = catalog.providers.single { it.id.value == "openai" }
        listOf("babbage-002", "chatgpt-image-latest", "computer-use-preview", "text-embedding-3-small", "gpt-3.5-turbo-instruct")
            .forEach { assertEquals(false, openai.capabilitiesFor(app.whittle.core.model.ModelId(it))?.toolCalling, it) }
        assertEquals(true, openai.capabilitiesFor(app.whittle.core.model.ModelId("gpt-5.6-terra"))?.toolCalling)
        assertEquals(true, openai.capabilitiesFor(app.whittle.core.model.ModelId("o3"))?.vision)
        assertEquals(null, openai.capabilitiesFor(app.whittle.core.model.ModelId("gpt-4o")), "unlisted chat models use the fallback")
    }

    @Test
    fun `OpenAI offers only the models it lists, OpenRouter every model`() {
        val catalog = assertIs<Outcome.Success<ProviderCatalog>>(
            loader.load(ConfigFiles.providerCatalog, ProviderCatalog.serializer(), ProviderCatalog.SCHEMA_VERSION),
        ).value
        val openai = catalog.providers.single { it.id.value == "openai" }
        // Current chat models that take function tools, streaming and reasoning effort through the Responses API.
        listOf(
            "gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna", "gpt-6-sol", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
            "gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.2", "gpt-5", "gpt-5-mini", "gpt-5-nano", "o3",
        ).forEach { assertEquals(true, openai.lists(app.whittle.core.model.ModelId(it)), it) }
        // Pro models (slow, and not all stream), deprecated ones, dated snapshots, and models that can't chat.
        listOf(
            "gpt-5.5-pro", "o3-pro", "gpt-5.4-nano", "gpt-4-turbo", "gpt-4", "gpt-4o", "gpt-live-1", "chat-latest",
            "gpt-5.6-cyber", "gpt-5.5-2026-04-23", "gpt-5.6-terra-mini", "gpt-image-2",
        )
            .forEach { assertEquals(false, openai.lists(app.whittle.core.model.ModelId(it)), it) }
        val openrouter = catalog.providers.single { it.id.value == "openrouter" }
        assertEquals(true, openrouter.lists(app.whittle.core.model.ModelId("anthropic/claude-opus-5.5")))
    }

    @Test
    fun `wrong schema version is rejected`() {
        val result = loader.load(ConfigFiles.harness, HarnessConfig.serializer(), HarnessConfig.SCHEMA_VERSION + 1)
        assertIs<Outcome.Failure>(result)
    }
}
