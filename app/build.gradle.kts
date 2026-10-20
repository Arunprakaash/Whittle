plugins {
    alias(libs.plugins.whittle.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.whittle"

    defaultConfig {
        applicationId = "app.whittle"
        versionCode = 1
        versionName = "0.1.0"
        // PostHog's public project key comes from your Gradle properties, never from the repo.
        // Without it, telemetry stays on the device.
        val postHogProjectKey = providers.gradleProperty("whittle.posthogProjectKey").orElse("").get()
        buildConfigField("String", "POSTHOG_PROJECT_KEY", "\"$postHogProjectKey\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":design:system"))
    implementation(project(":config:defaults"))
    implementation(project(":agent:runtime"))
    implementation(project(":policy:engine"))
    implementation(project(":llm:adapter-openai-compatible"))
    implementation(project(":llm:adapter-openai-responses"))
    implementation(project(":tools:presentation"))
    implementation(project(":tools:device"))
    implementation(project(":tools:web"))
    implementation(project(":tools:android"))
    implementation(project(":storage:secrets"))
    implementation(project(":storage:runs"))
    implementation(project(":observability:api"))
    implementation(project(":observability:posthog"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
