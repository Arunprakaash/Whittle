# Architecture

How Whittle is put together, drawn in Excalidraw. Each picture links to its source in `source/`, which opens at [excalidraw.com](https://excalidraw.com) (File › Open).

These were drawn while designing the harness; where they and the code differ, the code is right.

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

## Mini-app runtime and capability bridge

[![Mini-app runtime and capability bridge](d08_sandbox_bridge.png)](source/d08_sandbox_bridge.excalidraw)

## Trust boundaries and data flow

[![Trust boundaries and data flow](d09_trust_boundaries.png)](source/d09_trust_boundaries.excalidraw)

## Automations and scheduled agents

[![Automations and scheduled agents](d10_automations.png)](source/d10_automations.excalidraw)

## Apps, widgets and shortcuts — React Native, one codebase

[![Apps, widgets and shortcuts — React Native, one codebase](d11_mini_apps_widgets.png)](source/d11_mini_apps_widgets.excalidraw)

## Native APK export pipeline

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

## Build phases

[![Build phases](d18_build_phases.png)](source/d18_build_phases.excalidraw)
