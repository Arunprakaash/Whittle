import java.util.Properties

plugins {
    alias(libs.plugins.whittle.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.whittle"

    defaultConfig {
        applicationId = "app.whittle"
        // Commands run in Linux, whose programs live in the app's storage. Android lets an app run programs from
        // there only while it targets this SDK or lower, so Whittle ships on GitHub, not Play (docs/linux-workspace.md).
        targetSdk = libs.versions.androidLinuxTargetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 30
        versionName = "0.24.0"
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

    lint {
        // Whittle isn't on Play: its target SDK is chosen for Linux (see targetSdk above), not Play's minimum.
        disable += "ExpiredTargetSdkVersion"
    }
}

// The programs that start and unpack Linux (proot, tar) are Termux's Android builds, pinned in
// termux-runtimes.lock.json (update with ./gradlew :app:updateTermuxLock). Programs and libraries ship as
// native libraries, the only place Android lets an app run native code from, so the APK must extract them.
val termuxSpec = layout.projectDirectory.file("termux-runtimes.json")
val termuxLock = layout.projectDirectory.file("termux-runtimes.lock.json")
val runtimeAssetDirectory = "runtimes"
// Gradle's shared download cache for the pinned .debs; it outlives the build directory (CI caches it, see ci.yml).
val termuxDownloads = File(gradle.gradleUserHomeDir, "caches/whittle-termux")
val updateTermuxLock = tasks.register<UpdateTermuxLockTask>("updateTermuxLock") {
    specFile.set(termuxSpec)
    lockFile.set(termuxLock)
}
// Every pinned .deb, for CI to upload as the mirror termux-runtimes.json names: Termux keeps only current versions.
tasks.register<CollectTermuxDebsTask>("collectTermuxDebs") {
    specFile.set(termuxSpec)
    lockFile.set(termuxLock)
    downloadDirectory.set(termuxDownloads)
    outputDirectory.set(layout.buildDirectory.dir("termux-mirror"))
}
android.packaging.jniLibs.useLegacyPackaging = true
android.ndkVersion = libs.versions.ndk.get()

// The command sandbox (app/src/main/native/sandbox): every run_command starts through it, inside Linux. Linked
// statically so it needs neither Android's libc nor Linux's, and shipped as libwhittle_sandbox.so for the same ABIs
// as the runtimes (termux-runtimes.json); it's mounted into Linux read-only.
val sandboxProgram = "whittle_sandbox"
val sandboxExecutable = tasks.register<NativeExecutableTask>("sandboxExecutable") {
    source.set(layout.projectDirectory.file("src/main/native/sandbox/whittle_sandbox.c"))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    targets.set(mapOf("arm64-v8a" to "aarch64-linux-android"))
    minSdk.set(android.defaultConfig.minSdk ?: error("minSdk must be set"))
    programName.set(sandboxProgram)
    linkStatically.set(true)
    jniLibsDirectory.set(layout.buildDirectory.dir("generated/sandbox/jniLibs"))
}
android.defaultConfig.buildConfigField("String", "SANDBOX_PROGRAM", "\"lib$sandboxProgram.so\"")

android.defaultConfig.buildConfigField("String", "RUNTIME_ASSETS", "\"$runtimeAssetDirectory\"")

// The full-colour launcher icon is drawn from the beaver's sprite file; the one-colour ones are in res/drawable.
val spriteDrawables = tasks.register<GenerateSpriteDrawablesTask>("generateSpriteDrawables") {
    spriteFile.set(rootProject.layout.projectDirectory.file("docs/design/sprites/beaver.sprite.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sprites/res"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(spriteDrawables, GenerateSpriteDrawablesTask::outputDirectory)
        val runtime = tasks.register<TermuxRuntimeTask>("${variant.name}TermuxRuntime") {
            specFile.set(termuxSpec)
            lockFile.set(termuxLock)
            applicationId.set(variant.applicationId)
            assetDirectory.set(runtimeAssetDirectory)
            downloadDirectory.set(termuxDownloads)
            jniLibsDirectory.set(layout.buildDirectory.dir("generated/termux/${variant.name}/jniLibs"))
            assetsDirectory.set(layout.buildDirectory.dir("generated/termux/${variant.name}/assets"))
            // Builds read the lock file; when it's updated in the same run, read the new one.
            mustRunAfter(updateTermuxLock)
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(runtime, TermuxRuntimeTask::jniLibsDirectory)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(sandboxExecutable, NativeExecutableTask::jniLibsDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(runtime, TermuxRuntimeTask::assetsDirectory)
    }
}

dependencies {
    implementation(project(":design:system"))
    implementation(project(":config:defaults"))
    implementation(project(":agent:runtime"))
    implementation(project(":policy:engine"))
    implementation(project(":llm:adapter-openai-compatible"))
    implementation(project(":llm:adapter-openai-responses"))
    implementation(project(":llm:adapter-anthropic-messages"))
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
    implementation(project(":connectors:mcp"))
    implementation(libs.androidx.browser)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.apksig)

    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
}
