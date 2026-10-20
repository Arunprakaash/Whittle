<p align="center"><img src="docs/media/beaver.gif" width="176" alt="Whittle's beaver: idle, thinking, gnawing a stick, done"></p>

<h1 align="center">Whittle</h1>

<p align="center">An AI agent that lives on your Android phone. Any model, your own keys, no backend.</p>

<p align="center"><a href="https://github.com/Arunprakaash/Whittle/releases/latest">Download the latest APK</a></p>

Whittle gets things done the way Claude Code does on a computer, for people who aren't developers. It searches the
web, writes and runs code in a sandbox on the phone, builds apps you can open, share or install, and connects to
services like Supabase or any MCP server you add. It talks to the AI provider you choose with your own key, and
your keys never leave the phone.

<table>
  <tr>
    <td align="center"><img src="docs/media/web-task.gif" width="240" alt="A web research task: thinking, searching, answering with a source"><br>Research with sources</td>
    <td align="center"><img src="docs/media/build-app.gif" width="240" alt="Whittle building a tip calculator app, sped up"><br>Builds an app (4× speed)</td>
    <td align="center"><img src="docs/media/use-app.gif" width="240" alt="Using the tip calculator it built"><br>…that you use right away</td>
  </tr>
</table>

## What it can do

- **Work in plain steps.** Whittle says what it's about to do, does it, and shows each step. Long tasks get a plan
  you can follow, and keep running in the background with a live progress notification.
- **Search and read the web**, citing the pages it used.
- **Build apps.** Every app is a React + TypeScript project built on the phone. Open it in a sheet or full
  screen, share it, open it in the browser, or **install it as its own Android app** with its own name and icon.
- **Run code.** A sandboxed shell with Node.js, Python and git, offline unless a step needs the network.
- **Use your accounts.** Connect **Supabase** to build apps with shared data and sign-in, or add **any MCP
  server**. You can ask Whittle to add one; it asks on a card first.
- **Use the phone.** Contacts, calendar and message drafts, each behind Android's own permission.

## Safety and privacy

- **You approve what matters.** A deterministic policy engine, not the model, decides what runs by itself and
  what needs your OK (`policy-rules.json`, `model-trust.json`). Anything that sends, deletes or spends asks
  every time, and models without a trust rating can't use those tools at all.
- **Outside content can't take over.** Web pages and tool output are marked untrusted; once a task has read
  them, sending anything out needs your approval.
- **Keys stay on the phone**, encrypted with the Android Keystore. Sign-ins to services use OAuth in the browser,
  and tokens never pass through the chat.
- **Analytics are yours to switch off.** Usage data is metadata only. Screen recordings show the app's
  interface but mask your messages, answers, chat titles and apps.

## Install

Download `Whittle-<version>.apk` from [Releases](https://github.com/Arunprakaash/Whittle/releases) on a 64-bit
ARM phone with Android 8 or later. Updates install over earlier versions and keep your chats. Then connect a
provider (OpenAI or OpenRouter) with your API key.

## How it's built

The architecture is drawn in [docs/architecture](docs/architecture/README.md): the
[agent run loop](docs/architecture/d04_agent_run_loop.png), the
[policy engine](docs/architecture/d05_policy_engine.png), the
[trust boundaries](docs/architecture/d09_trust_boundaries.png), the
[APK export pipeline](docs/architecture/d12_native_apk_export.png) and more. The Excalidraw sources are next to
the pictures.

The design system lives in [docs/design](docs/design): tokens (`tokens/whittle.tokens.json`, W3C DTCG) that
generate the app's colors, type, spacing and motion, the beaver (`sprites/beaver.sprite.json`) that generates
its animation and every launcher and notification icon, and a design-system page built from both
(`site/whittle-design-system.html`).

### Modules

| Module | What it does |
|---|---|
| `core:common` | `Outcome`, typed `HarnessError`, `Secret`, PKCE, clock and id abstractions, `ContentCipher` |
| `core:model` | Canonical, provider-neutral messages, tool calls, provenance, token usage |
| `core:config` | `ConfigLoader` (versioned, strict JSON), `HarnessConfig`, prompt templates |
| `config:defaults` | Reviewed default config shipped with the app (`src/main/resources/whittle/config`) |
| `llm:api` | `ModelAdapter`, provider and model catalogs, trust tiers, stream events |
| `llm:http` | Shared streaming plumbing: Server-Sent Events, provider URLs and auth headers |
| `llm:adapter-openai-compatible` | Streaming Chat Completions adapter (OpenRouter, Groq, Together, Ollama, …) and model lister |
| `llm:adapter-openai-responses` | OpenAI Responses API adapter: tools with reasoning, hosted web search, `store: false` |
| `llm:openrouter-auth` | One-tap OpenRouter sign-in with OAuth PKCE |
| `tools:api` | `ToolDefinition`, `ToolRegistry` (built-in and connected tools), risk tiers, argument validation |
| `tools:presentation` | Plain-language step labels and approval cards for each tool, from `tool-presentation.json` |
| `tools:device`, `tools:android` | Clock, contacts, calendar, message drafts |
| `tools:web` | Web fetch over https; output is untrusted |
| `tools:workspace` | Files, the sandboxed shell (`run_command`) and app builds (`build_web_app`, `show_artifact`) |
| `connectors:mcp` | MCP over Streamable HTTP with OAuth 2.1 (discovery, dynamic registration, PKCE), tokens or no sign-in |
| `policy:engine` | Deterministic allow / ask / deny engine driven by `policy-rules.json` |
| `agent:runtime` | `AgentRunner`: the agent loop with checkpoints, plans, approvals and resume |
| `observability:api`, `observability:posthog` | Vendor-neutral telemetry with consent filtering; PostHog adapter and replay privacy |
| `storage:secrets`, `storage:runs`, `storage:chats` | Encrypted vault (Tink + Keystore), run checkpoints and chats (Room, encrypted) |
| `design:system` | Compose design system generated from the tokens and the beaver sprite |
| `export:shell` | The tiny WebView app that installed apps are packed into |
| `app` | The Android app |
| `dev:cli` | Developer command-line runner for the real loop with your own key |

## Configuration

Nothing that may change lives in code. Defaults are in `config/defaults/src/main/resources/whittle/config`:
`harness-config.json`, `provider-catalog.json`, `model-trust.json`, `model-defaults.json`, `policy-rules.json`,
`tool-presentation.json`, `connectors.json`, `app-defaults.json`, `agent-messages.json` and
`prompts/agent-system.md`. Every file has a `schemaVersion`, and unknown keys are rejected.

## Build and test

Requires JDK 17+ (Android Studio's bundled JDK works) and the Android SDK with NDK 28.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test :app:assembleRelease
```

Optional Gradle properties, kept in `~/.gradle/gradle.properties` and never in the repo:
`whittle.posthogProjectKey` (analytics), `whittle.replayShowContent=true` (your own test builds show content in
recordings) and the release signing properties.

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
Use `--approve always|never` for non-interactive runs.
