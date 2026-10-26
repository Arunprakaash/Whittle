import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Compose Multiplatform library: one UI codebase for Android, desktop (the Mac app) and the browser. Desktop and the
 * browser both draw with Skia, so they share a `skiaMain` source set for what Android does through its own platform.
 * The module sets its Android namespace in `kotlin { android { } }`.
 */
class MultiplatformComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.plugins("kotlin-multiplatform"))
        pluginManager.apply(libs.plugins("android-kotlin-multiplatform-library"))
        pluginManager.apply(libs.plugins("compose-multiplatform"))
        pluginManager.apply(libs.plugins("kotlin-compose"))

        extensions.configure<KotlinMultiplatformExtension> {
            val jvmTarget = libs.requiredVersion("jvmTarget")
            jvm(DESKTOP) { compilerOptions { this.jvmTarget.set(JvmTarget.fromTarget(jvmTarget)) } }
            @OptIn(ExperimentalWasmDsl::class)
            wasmJs { browser() }
            applyDefaultHierarchyTemplate()

            sourceSets.apply {
                val skiaMain = create("skiaMain") { dependsOn(getByName("commonMain")) }
                getByName("${DESKTOP}Main").dependsOn(skiaMain)
                getByName("wasmJsMain").dependsOn(skiaMain)
                getByName("commonMain").dependencies {
                    implementation(libs.requiredLibrary("kotlinx-coroutines-core"))
                    api(libs.requiredLibrary("compose-mp-runtime"))
                    api(libs.requiredLibrary("compose-mp-foundation"))
                    api(libs.requiredLibrary("compose-mp-ui"))
                    api(libs.requiredLibrary("compose-mp-material3"))
                    api(libs.requiredLibrary("compose-mp-resources"))
                }
                getByName("${DESKTOP}Test").dependencies {
                    implementation(libs.requiredLibrary("kotlin-test-junit"))
                }
            }
        }
    }

    private companion object {
        /** The JVM target's name, which names its source sets (`desktopMain`). */
        const val DESKTOP = "desktop"
    }
}
