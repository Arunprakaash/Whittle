# Mobile Agent

A general AI agent harness for Android: any model, no backend, deterministic safety. Phase 1 contains the
core harness only (no UI yet).

Architecture diagrams: `~/Desktop/mobile-agent-architecture-diagrams/`.

## Modules

| Module | What it does |
|---|---|
| `core:common` | `Outcome`, typed `HarnessError`, `Secret`, clock and id abstractions, `ContentCipher` |
| `core:model` | Canonical, provider-neutral messages, tool calls, provenance, token usage |
| `core:config` | `ConfigLoader` (versioned, strict JSON), `HarnessConfig`, prompt templates |
| `config:defaults` | Reviewed default config shipped with the app (`src/main/resources/mobileagent/config`) |
| `llm:api` | `ModelAdapter`, provider and model catalogs, trust tiers, stream events |
| `llm:http` | Shared streaming plumbing: Server-Sent Events decoding, provider URLs and auth headers |
| `llm:adapter-openai-compatible` | Streaming Chat Completions adapter (OpenRouter, Groq, Together, Ollama, …) and model lister |
| `llm:adapter-openai-responses` | OpenAI Responses API adapter: tools with reasoning, encrypted reasoning carried between steps, `store: false` |
| `llm:openrouter-auth` | One-tap OpenRouter sign-in with OAuth PKCE |
| `tools:api` | `ToolDefinition`, `ToolRegistry`, risk tiers, argument validation |
| `policy:engine` | Deterministic allow / ask / deny engine driven by `policy-rules.json` |
| `agent:runtime` | `AgentRunner`: the agent loop with checkpoints, approvals, resume |
| `observability:api` | Vendor-neutral telemetry with consent filtering |
| `observability:posthog` | PostHog adapter (events, `$ai_span`, `$ai_generation`) — Android |
| `storage:secrets` | Encrypted on-device vault (Tink + Android Keystore + DataStore) — Android |
| `storage:runs` | Encrypted Room store for run checkpoints — Android |
| `dev:cli` | Developer command-line runner for testing the real loop with your own key |

## Configuration

Nothing that may change lives in code. Defaults are in `config/defaults/src/main/resources/mobileagent/config`:
`harness-config.json`, `provider-catalog.json`, `model-trust.json`, `model-defaults.json`, `policy-rules.json`,
`openrouter-auth.json`, `agent-messages.json` and `prompts/agent-system.md`. Every file has a `schemaVersion`
and unknown keys are rejected. `openrouter-auth.json` contains a placeholder `callbackUrl` that must be replaced
with the app's verified App Link before release.

## Build and test

Requires JDK 17+ (Android Studio's bundled JDK works) and the Android SDK.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test
```

## Try the agent from the terminal

Store your key in a local file outside the repo (never commit it):

```bash
dev/cli/save-dev-key.sh openai
```

Then:

```bash
./gradlew :dev:cli:installDist
dev/cli/build/install/cli/bin/cli --provider openai --list-models
dev/cli/build/install/cli/bin/cli --provider openai --model <model-id> --prompt "What time is it in Tokyo?"
```

Pick the reasoning effort with `--reasoning none|low|medium|high`; the default comes from `model-defaults.json`.
How each provider takes the effort (field path and values) is set per provider in `provider-catalog.json`.

The CLI ships two sample tools: `device_clock_now` (read-only) and `web_fetch` (network, untrusted output),
which exercise the approval and taint rules. Use `--approve always|never` for non-interactive runs.
