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
    // Kotlin's browser build adds its own tool repositories; these settings ones are used instead.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // The browser build's tools, which Kotlin downloads: Node.js, Yarn and Binaryen.
        ivy("https://nodejs.org/dist") {
            name = "Node.js"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen"
            patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
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
    ":llm:adapter-anthropic-messages",
    ":llm:openrouter-auth",
    ":tools:api",
    ":tools:presentation",
    ":tools:device",
    ":tools:web",
    ":tools:android",
    ":tools:workspace",
    ":connectors:mcp",
    ":policy:engine",
    ":agent:runtime",
    ":observability:api",
    ":observability:posthog",
    ":storage:secrets",
    ":storage:runs",
    ":storage:chats",
    ":design:system",
    ":feature:chat",
    ":feature:files",
    ":feature:settings",
    ":feature:link",
    ":app",
    ":companion",
    ":dev:cli",
)
