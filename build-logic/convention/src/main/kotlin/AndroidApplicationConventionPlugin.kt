import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** The Android app: Compose UI, shared SDK levels from the version catalog. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.plugins("android-application"))
        pluginManager.apply(libs.plugins("kotlin-compose"))

        val jvmTarget = JavaVersion.toVersion(libs.requiredVersion("jvmTarget"))
        extensions.configure<ApplicationExtension> {
            compileSdk = libs.requiredVersion("androidCompileSdk").toInt()
            defaultConfig {
                minSdk = libs.requiredVersion("androidMinSdk").toInt()
                targetSdk = libs.requiredVersion("androidTargetSdk").toInt()
            }
            compileOptions {
                sourceCompatibility = jvmTarget
                targetCompatibility = jvmTarget
            }
            buildFeatures { compose = true }
        }
        addComposeDependencies()
        dependencies {
            add("implementation", libs.requiredLibrary("kotlinx-coroutines-core"))
            add("testImplementation", libs.requiredLibrary("kotlin-test-junit"))
            add("testImplementation", libs.requiredLibrary("kotlinx-coroutines-test"))
        }
    }
}
