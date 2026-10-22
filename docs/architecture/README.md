# Architecture

How Whittle was designed, drawn in Excalidraw. Each picture links to its source in `source/`, which opens at
[excalidraw.com](https://excalidraw.com) (File › Open).

These were drawn before the code. The core of the design held: one agent loop on the phone, checkpoints, a
deterministic policy engine, one message format, taint tracking and approvals. The plan for apps and code did not.
React Native mini-apps, widgets, APK export, Pyodide and a JavaScript sandbox were replaced by a real Ubuntu on the
phone, where apps are Vite servers shown in a live preview. Where a picture and the code differ, the code is right;
the [README](../../README.md) and [Linux workspace](../linux-workspace.md) describe what's built.

## How each picture compares with the code

| Diagram | Status | What's different now |
| --- | --- | --- |
| d01 System context | Partly | Providers are OpenAI, OpenRouter and any OpenAI-compatible server. No user-owned cloud, report form, Developer Console or package installs: nothing is exported |
| d02 Layered architecture | Partly | No code runtimes, artifact builders or capability bridge: commands run in Ubuntu under proot, behind a seccomp sandbox. Surfaces are the chat, its cards and notifications: no share target, Quick Settings tile, assistant session, widgets or shortcuts |
| d03 Module map | Partly | No `runtime/`, `export/` or `automation/` modules, and no Anthropic or Gemini adapters. The README lists the real modules |
| d04 Agent run loop | Holds | Except auto-fix and scheduled jobs, which aren't built |
| d05 Policy engine | Holds | The README has the full order, which adds trust-tier denials and personal data |
| d06 Model layer | Partly | Adapters: OpenAI-compatible Chat Completions and OpenAI Responses. OpenRouter sign-in is as drawn |
| d07 Tool registry | Partly | Tools run in Kotlin or in Ubuntu. No JS sandbox, Pyodide, provider sandbox, user cloud or artifact builders. Tiers add workspace and web read |
| d08 Mini-app runtime and bridge | Replaced | Apps are Vite servers in Linux, opened from `127.0.0.1` in a WebView |
| d09 Trust boundaries | Holds | Except APK export, which no longer exists |
| d10 Automations | Not built yet | In review |
| d11 React Native apps and widgets | Replaced | No React Native, widgets or shortcuts |
| d12 Native APK export | Replaced | Removed in 0.10.0 |
| d13 Connectors | Holds | Sign-in returns to a loopback redirect on `127.0.0.1`, not an App Link. MCP over Streamable HTTP and SSE |
| d14 Run lifecycle | Partly | No auto-fix or roll back, and no WorkManager: background runs use the foreground service |
| d15 Data and storage | Partly | Nothing is backed up. One shared workspace, plus Ubuntu, in the app's files. No signing key or mini-app data |
| d16 Configuration and registries | Partly | No capability registry or project scaffold. The README lists the config files |
| d17 Observability | Partly | No export or automation events. Events carry metadata only |
| d18 Build phases | Replaced | What shipped is in the [releases](https://github.com/Arunprakaash/Whittle/releases) |

## Mobile Agent Harness — system context

[![Mobile Agent Harness — system context](d01_system_context.png)](source/d01_system_context.excalidraw)

## Layered architecture (inside the app)

[![Layered architecture (inside the app)](d02_layered_architecture.png)](source/d02_layered_architecture.excalidraw)

## Gradle module map

[![Gradle module map](d03_module_map.png)](source/d03_module_map.excalidraw)

## Agent run loop

[![Agent run loop](d04_agent_run_loop.png)](source/d04_agent_run_loop.excalidraw)

## Policy engine decision flow

[![Policy engine decision flow](d05_policy_engine.png)](source/d05_policy_engine.excalidraw)

## Model layer — any model, one internal format

[![Model layer — any model, one internal format](d06_model_layer.png)](source/d06_model_layer.excalidraw)

## Tool registry and execution sites

[![Tool registry and execution sites](d07_tool_registry.png)](source/d07_tool_registry.excalidraw)

## Mini-app runtime and capability bridge (replaced)

[![Mini-app runtime and capability bridge](d08_sandbox_bridge.png)](source/d08_sandbox_bridge.excalidraw)

## Trust boundaries and data flow

[![Trust boundaries and data flow](d09_trust_boundaries.png)](source/d09_trust_boundaries.excalidraw)

## Automations and scheduled agents (not built yet)

[![Automations and scheduled agents](d10_automations.png)](source/d10_automations.excalidraw)

## Apps, widgets and shortcuts — React Native, one codebase (replaced)

[![Apps, widgets and shortcuts — React Native, one codebase](d11_mini_apps_widgets.png)](source/d11_mini_apps_widgets.excalidraw)

## Native APK export pipeline (replaced)

[![Native APK export pipeline](d12_native_apk_export.png)](source/d12_native_apk_export.excalidraw)

## Connectors — MCP and OAuth from the phone

[![Connectors — MCP and OAuth from the phone](d13_connectors_oauth.png)](source/d13_connectors_oauth.excalidraw)

## Run lifecycle (state machine)

[![Run lifecycle (state machine)](d14_run_lifecycle.png)](source/d14_run_lifecycle.excalidraw)

## Data and storage map

[![Data and storage map](d15_data_storage.png)](source/d15_data_storage.excalidraw)

## Configuration and registries

[![Configuration and registries](d16_config_registries.png)](source/d16_config_registries.excalidraw)

## Observability and product analytics — one tool: PostHog

[![Observability and product analytics — one tool: PostHog](d17_observability.png)](source/d17_observability.excalidraw)

## Build phases (replaced)

[![Build phases](d18_build_phases.png)](source/d18_build_phases.excalidraw)
