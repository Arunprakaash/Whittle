import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android library with Jetpack Compose: the design system and feature UI. */
class AndroidComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.plugins("whittle-android-library"))
        pluginManager.apply(libs.plugins("kotlin-compose"))
        extensions.configure<LibraryExtension> {
            buildFeatures { compose = true }
        }
        addComposeDependencies()
    }
}
