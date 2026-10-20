import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** One Compose BOM for every module, so all Compose artifacts stay on matching versions. */
internal fun Project.addComposeDependencies() {
    dependencies {
        val bom = platform(libs.requiredLibrary("androidx-compose-bom"))
        add("implementation", bom)
        add("implementation", libs.requiredLibrary("androidx-compose-ui"))
        add("implementation", libs.requiredLibrary("androidx-compose-ui-graphics"))
        add("implementation", libs.requiredLibrary("androidx-compose-ui-tooling-preview"))
        add("implementation", libs.requiredLibrary("androidx-compose-foundation"))
        add("implementation", libs.requiredLibrary("androidx-compose-material3"))
        add("debugImplementation", bom)
        add("debugImplementation", libs.requiredLibrary("androidx-compose-ui-tooling"))
    }
}
