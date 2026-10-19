import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.requiredVersion(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("Version '$alias' is missing from libs.versions.toml") }.requiredVersion

internal fun VersionCatalog.requiredLibrary(alias: String) =
    findLibrary(alias).orElseThrow { IllegalStateException("Library '$alias' is missing from libs.versions.toml") }
