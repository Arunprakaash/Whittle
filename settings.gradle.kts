pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "whittle"

include(
    ":core:common",
    ":core:model",
    ":core:config",
    ":config:defaults",
    ":llm:api",
    ":llm:http",
    ":llm:adapter-openai-compatible",
    ":llm:adapter-openai-responses",
    ":llm:openrouter-auth",
    ":tools:api",
    ":tools:presentation",
    ":tools:device",
    ":tools:web",
    ":policy:engine",
    ":agent:runtime",
    ":observability:api",
    ":observability:posthog",
    ":storage:secrets",
    ":storage:runs",
    ":design:system",
    ":app",
    ":dev:cli",
)
