package app.whittle.core.config

/** The configuration documents the harness knows about. Paths are relative to the config root. */
object ConfigFiles {
    val harness = ConfigName("harness-config.json")
    val providerCatalog = ConfigName("provider-catalog.json")
    val modelTrust = ConfigName("model-trust.json")
    val modelDefaults = ConfigName("model-defaults.json")
    val policyRules = ConfigName("policy-rules.json")
    val openRouterAuth = ConfigName("openrouter-auth.json")
    val agentMessages = ConfigName("agent-messages.json")
    val agentSystemPrompt = ConfigName("prompts/agent-system.md")
}
