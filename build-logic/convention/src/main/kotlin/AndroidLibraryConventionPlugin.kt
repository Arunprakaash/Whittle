import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Android library using AGP's built-in Kotlin support. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        val jvmTarget = JavaVersion.toVersion(libs.requiredVersion("jvmTarget"))
        extensions.configure<LibraryExtension> {
            compileSdk {
                version = release(libs.requiredVersion("androidCompileSdk").toInt()) {
                    minorApiLevel = libs.requiredVersion("androidCompileSdkMinor").toInt()
                }
            }
            defaultConfig {
                minSdk = libs.requiredVersion("androidMinSdk").toInt()
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = jvmTarget
                targetCompatibility = jvmTarget
            }
        }

        dependencies {
            add("implementation", libs.requiredLibrary("kotlinx-coroutines-core"))
            add("testImplementation", libs.requiredLibrary("kotlin-test-junit"))
            add("testImplementation", libs.requiredLibrary("kotlinx-coroutines-test"))
        }
    }
}
