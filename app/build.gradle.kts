import java.util.Properties

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
        // Session recordings mask all text and images unless this build opts out, for the developer's own
        // testing. Set whittle.replayShowContent=true in your Gradle properties; never in shipped builds.
        val replayShowContent = providers.gradleProperty("whittle.replayShowContent").orElse("false").get().toBoolean()
        buildConfigField("boolean", "REPLAY_SHOW_CONTENT", replayShowContent.toString())
        // PostHog's own logs (what it sends, why replay is or isn't recording), for diagnosing a build.
        val postHogDebug = providers.gradleProperty("whittle.posthogDebug").orElse("false").get().toBoolean()
        buildConfigField("boolean", "POSTHOG_DEBUG", postHogDebug.toString())
    }

    // Release signing comes from a properties file outside the repo (storeFile, storePassword, keyAlias,
    // keyPassword). Without it the release APK is built unsigned.
    val releaseSigning = providers.gradleProperty("whittle.releaseSigningFile")
        .map { file(it) }
        .orElse(provider { File(System.getProperty("user.home"), ".whittle/release-signing.properties") })
        .get()
    if (releaseSigning.isFile) {
        val properties = Properties().apply { releaseSigning.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = file(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        getByName("debug") {
            // Dev builds install next to the release app instead of replacing it.
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

// esbuild's WebAssembly build, pinned and checked against its npm integrity, ships as an app asset next to
// the host page in src/main/assets/web-build. The paths here are the ones host.js loads.
val esbuildWasmAssets = tasks.register<NpmPackageAssetsTask>("esbuildWasmAssets") {
    registry.set("https://registry.npmjs.org")
    packageName.set("esbuild-wasm")
    version.set(libs.versions.esbuildWasm)
    integrity.set(libs.versions.esbuildWasmIntegrity)
    files.set(
        mapOf(
            "esm/browser.min.js" to "web-build/esbuild/browser.min.js",
            "esbuild.wasm" to "web-build/esbuild/esbuild.wasm",
            "LICENSE.md" to "web-build/esbuild/LICENSE.md",
        ),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/assets/esbuildWasm"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(esbuildWasmAssets, NpmPackageAssetsTask::outputDirectory)
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
    implementation(project(":tools:workspace"))
    implementation(project(":storage:secrets"))
    implementation(project(":storage:runs"))
    implementation(project(":storage:chats"))
    implementation(project(":observability:api"))
    implementation(project(":observability:posthog"))
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.okhttp.mockwebserver)
}
