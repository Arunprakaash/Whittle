import java.time.Year
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/** Whittle on a computer: the phone's chats in a Mac window or a browser tab, drawn by the same design system. */
kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get())) } }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "companion.js" } }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":feature:link"))
            implementation(libs.compose.mp.resources)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        // The desktop main thread is Swing's.
        named("desktopMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
            }
        }
        wasmJsMain {
            dependencies { implementation(libs.ktor.client.js) }
            // The page serves the design system's font files beside itself.
            resources.srcDir(rootProject.file("design/system/src/androidMain/res/font"))
        }
        named("desktopTest") { dependencies { implementation(libs.kotlin.test.junit) } }
    }
}

/**
 * Signing and notarizing come from a properties file outside the repo, as the APK's do: identity (the Developer ID
 * Application certificate's name), keychain (optional), and appleId, password (an app-specific one) and teamId for
 * notarizing. Without it the app is signed ad hoc, which runs here but which Gatekeeper stops on other Macs.
 */
val macSigningFile: File = providers.gradleProperty("whittle.macSigningFile")
    .map { file(it) }
    .orElse(provider { File(System.getProperty("user.home"), ".whittle/mac-signing.properties") })
    .get()
val macSigning: Properties? = macSigningFile.takeIf { it.isFile }?.let { file -> Properties().apply { file.inputStream().use(::load) } }

fun Properties.required(key: String): String = getProperty(key) ?: error("$macSigningFile has no $key")

compose.desktop {
    application {
        mainClass = "app.whittle.companion.MainKt"
        // Packaging needs a JDK with jpackage, which Android Studio's doesn't have: -Pwhittle.packageJdk=<its home>.
        providers.gradleProperty("whittle.packageJdk").orNull?.let { javaHome = it }
        // The Mac app, as a .dmg: Whittle with its beaver, the same version as the phone's.
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "Whittle"
            packageVersion = libs.versions.whittle.get()
            description = "Whittle on your phone, in a window on this Mac."
            vendor = "Whittle"
            copyright = "© ${Year.now()} Whittle"
            // What the app's libraries reach in the Java runtime (suggestRuntimeModules lists them); preferences keep
            // the phone it reached last.
            modules("java.instrument", "java.management", "java.prefs", "jdk.unsupported")
            macOS {
                bundleID = "app.whittle.computer"
                dockName = "Whittle"
                appCategory = "public.app-category.productivity"
                minimumSystemVersion = "12.0"
                iconFile.set(project.file("icons/Whittle.icns"))
                // The phone is on this Wi-Fi, so macOS asks before the app's ssh reaches it; this is the reason it shows.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>Whittle reaches your phone over this network, through ssh.</string>
                    """.trimIndent()
                }
                // Signed apps run under the hardened runtime, with Compose's entitlements for a Java app (the JIT,
                // and the libraries it ships). There's no sandbox: the app runs this Mac's ssh, which reads ~/.ssh.
                macSigning?.let { properties ->
                    signing {
                        sign.set(true)
                        identity.set(properties.required("identity"))
                        properties.getProperty("keychain")?.let { keychain.set(it) }
                    }
                    notarization {
                        appleID.set(properties.required("appleId"))
                        password.set(properties.required("password"))
                        teamID.set(properties.required("teamId"))
                    }
                }
            }
        }
        // Shrinking a desktop app saves little and can break what's reached by reflection (Ktor, serialization).
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

/** The machine this builds on: a .dmg holds one Java runtime, so it runs on Macs with the same kind of chip. */
val macArchitecture = when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "arm64"
    else -> "x64"
}

/** The release .dmg, named as it's attached to a GitHub release: Whittle-<version>-<arm64|x64>.dmg in dist/. */
tasks.register<Copy>("releaseDmg") {
    group = "distribution"
    description = "Builds the release .dmg and copies it to dist/ under its release name."
    from(tasks.named("packageReleaseDmg"))
    // A signed .dmg is notarized and stapled where it was built, before it's copied.
    if (macSigning != null) dependsOn("notarizeReleaseDmg")
    include("*.dmg")
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "Whittle-${libs.versions.whittle.get()}-$macArchitecture.dmg" }
}

compose.resources {
    packageOfResClass = "app.whittle.companion.resources"
}
