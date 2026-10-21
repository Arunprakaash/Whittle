import java.util.Properties

plugins {
    alias(libs.plugins.whittle.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.whittle"

    defaultConfig {
        applicationId = "app.whittle"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 10
        versionName = "0.9.0"
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

// The command-line tools run_command offers (Node, npm, Python, git...) are Termux's Android builds, pinned
// in termux-runtimes.lock.json (update with ./gradlew :app:updateTermuxLock). Programs and libraries ship as
// native libraries, the only place Android lets an app run native code from, so the APK must extract them.
val termuxSpec = layout.projectDirectory.file("termux-runtimes.json")
val termuxLock = layout.projectDirectory.file("termux-runtimes.lock.json")
val runtimeAssetDirectory = "runtimes"
val updateTermuxLock = tasks.register<UpdateTermuxLockTask>("updateTermuxLock") {
    specFile.set(termuxSpec)
    lockFile.set(termuxLock)
}
android.packaging.jniLibs.useLegacyPackaging = true
android.ndkVersion = libs.versions.ndk.get()

// The command sandbox (app/src/main/native/sandbox): every run_command starts through it. Built for the same
// ABIs the runtimes ship for (termux-runtimes.json), as libwhittle_sandbox.so in the native-library folder.
val sandboxProgram = "whittle_sandbox"
val sandboxExecutable = tasks.register<NativeExecutableTask>("sandboxExecutable") {
    source.set(layout.projectDirectory.file("src/main/native/sandbox/whittle_sandbox.c"))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    targets.set(mapOf("arm64-v8a" to "aarch64-linux-android"))
    minSdk.set(android.defaultConfig.minSdk ?: error("minSdk must be set"))
    programName.set(sandboxProgram)
    jniLibsDirectory.set(layout.buildDirectory.dir("generated/sandbox/jniLibs"))
}
android.defaultConfig.buildConfigField("String", "SANDBOX_PROGRAM", "\"lib$sandboxProgram.so\"")

// Exported apps start from the shell app (export/shell): its unsigned release APK ships as an asset, and the
// placeholders it was built with are what Whittle replaces for each app.
val exportShellAsset = "export/shell.apk"
val exportShellApk = tasks.register<FileAssetTask>("exportShellAsset") {
    dependsOn(":export:shell:assembleRelease")
    source.set(project(":export:shell").layout.buildDirectory.file("outputs/apk/release/shell-release-unsigned.apk"))
    assetPath.set(exportShellAsset)
    assetsDirectory.set(layout.buildDirectory.dir("generated/exportShell/assets"))
}
android.defaultConfig.buildConfigField("String", "EXPORT_SHELL_ASSET", "\"$exportShellAsset\"")
// AppPackagerTest packs the real shell APK.
tasks.withType<Test>().configureEach {
    val shellApk = project(":export:shell").layout.buildDirectory.file("outputs/apk/release/shell-release-unsigned.apk")
    dependsOn(":export:shell:assembleRelease")
    inputs.file(shellApk)
    systemProperty("whittle.exportShell.apk", shellApk.get().asFile.absolutePath)
    systemProperty("whittle.exportShell.testOutput", layout.buildDirectory.dir("tmp/exportShellTest").get().asFile.absolutePath)
}
android.defaultConfig.buildConfigField(
    "String", "EXPORT_PACKAGE_PLACEHOLDER", "\"${providers.gradleProperty("whittle.exportShell.packagePlaceholder").get()}\"",
)
android.defaultConfig.buildConfigField(
    "String", "EXPORT_LABEL_PLACEHOLDER", "\"${providers.gradleProperty("whittle.exportShell.labelPlaceholder").get()}\"",
)
android.defaultConfig.buildConfigField("String", "RUNTIME_ASSETS", "\"$runtimeAssetDirectory\"")

// The launcher, themed and notification icons are drawn from the beaver's sprite file.
val spriteDrawables = tasks.register<GenerateSpriteDrawablesTask>("generateSpriteDrawables") {
    spriteFile.set(rootProject.layout.projectDirectory.file("docs/design/sprites/beaver.sprite.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sprites/res"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(spriteDrawables, GenerateSpriteDrawablesTask::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(esbuildWasmAssets, NpmPackageAssetsTask::outputDirectory)
        val runtime = tasks.register<TermuxRuntimeTask>("${variant.name}TermuxRuntime") {
            specFile.set(termuxSpec)
            lockFile.set(termuxLock)
            applicationId.set(variant.applicationId)
            assetDirectory.set(runtimeAssetDirectory)
            downloadDirectory.set(File(gradle.gradleUserHomeDir, "caches/whittle-termux"))
            jniLibsDirectory.set(layout.buildDirectory.dir("generated/termux/${variant.name}/jniLibs"))
            assetsDirectory.set(layout.buildDirectory.dir("generated/termux/${variant.name}/assets"))
            // Builds read the lock file; when it's updated in the same run, read the new one.
            mustRunAfter(updateTermuxLock)
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(runtime, TermuxRuntimeTask::jniLibsDirectory)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(sandboxExecutable, NativeExecutableTask::jniLibsDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(exportShellApk, FileAssetTask::assetsDirectory)
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
    implementation(libs.apksig)
    implementation(project(":connectors:mcp"))
    implementation(libs.androidx.browser)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
}
