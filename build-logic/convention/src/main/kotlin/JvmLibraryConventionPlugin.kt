import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Pure Kotlin/JVM library: no Android dependency, unit-testable on the JVM. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        // Several modules share a leaf name (":llm:api", ":tools:api"), so jars are named after the full path.
        extensions.configure<BasePluginExtension> {
            archivesName.set(path.removePrefix(":").replace(":", "-"))
        }

        val jvmTarget = libs.requiredVersion("jvmTarget")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.toVersion(jvmTarget)
            targetCompatibility = JavaVersion.toVersion(jvmTarget)
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                this.jvmTarget.set(JvmTarget.fromTarget(jvmTarget))
                allWarningsAsErrors.set(true)
            }
        }
        tasks.withType<Test>().configureEach { useJUnit() }

        dependencies {
            add("implementation", libs.requiredLibrary("kotlinx-coroutines-core"))
            add("testImplementation", libs.requiredLibrary("kotlin-test-junit"))
            add("testImplementation", libs.requiredLibrary("kotlinx-coroutines-test"))
        }
    }
}
