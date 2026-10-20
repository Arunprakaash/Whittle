package app.whittle.tools.workspace

import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** How build_web_app bundles a TypeScript or React project from a chat's workspace into a web app. */
@Serializable
data class WebBuildConfig(
    override val schemaVersion: Int,
    /** Where built files go when the agent doesn't say. Always a folder inside the workspace, never its root. */
    val defaultOutDir: String,
    /** Local scripts with these extensions are bundled: an entry file, or the ones a page's script tags load. */
    val entryExtensions: List<String>,
    /** Pages with these extensions are rewritten to load the bundles; any other entry is a script. */
    val pageExtensions: List<String>,
    /** Tried in order for imports written without an extension, as bundlers do. */
    val resolveExtensions: List<String>,
    /** esbuild loader for each file extension, e.g. ".tsx" to "tsx", ".png" to "dataurl". Anything else loads as JavaScript. */
    val loaders: Map<String, String>,
    /** Passed to esbuild as they are: target, format, jsx, minify and so on. */
    val esbuildOptions: JsonObject,
    /** Whether the bundles are ES modules, so the page loads them with type="module". Must match esbuildOptions.format. */
    val moduleOutput: Boolean,
    val packages: PackageCdnConfig,
    /** Folder names the build never reads, at any depth (besides the output folder). */
    val ignoredDirectories: List<String>,
    /** A workspace with more files than this is refused instead of read. */
    val maxSourceFiles: Int,
    /** Errors and warnings reported back to the agent, each list cut to this many. */
    val maxMessages: Int,
    /** A build that takes longer is stopped, and the bundler restarts for the next one. */
    val timeoutSeconds: Int,
    val host: WebBuildHostConfig,
) : VersionedConfig {
    init {
        require(defaultOutDir.isNotBlank()) { "defaultOutDir must name a folder" }
        require(entryExtensions.isNotEmpty() && pageExtensions.isNotEmpty() && resolveExtensions.isNotEmpty()) { "Extension lists must not be empty" }
        require((entryExtensions + pageExtensions + resolveExtensions + loaders.keys).all { it.startsWith(".") }) { "Extensions start with a dot" }
        require(maxSourceFiles > 0 && maxMessages > 0 && timeoutSeconds > 0) { "Web build limits must be positive" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("web-build.json")

        /** The page made for a script entry: `{{title}}` and `{{entry}}` (the script's path from the workspace root). */
        val PAGE_TEMPLATE = ConfigName("web-build-page.html")
    }
}

/**
 * Bare imports such as "react-dom/client" load from a package CDN as `<baseUrl>/<name>@<version><subpath>?<query>`,
 * with the version from the workspace's package manifest when it lists one.
 */
@Serializable
data class PackageCdnConfig(
    val baseUrl: String,
    val query: String,
    val manifestFile: String,
    /** Manifest sections that name package versions, read in order; the first mention wins. */
    val manifestFields: List<String>,
    /** Modules download only from https on these hosts: the CDN's own, and any the agent imports by full URL. */
    val allowedHosts: List<String>,
    /** A larger module is refused. */
    val maxModuleBytes: Long,
) {
    init {
        require(baseUrl.startsWith("https://")) { "packages.baseUrl must be https" }
        require(allowedHosts.isNotEmpty()) { "packages.allowedHosts must not be empty" }
        require(maxModuleBytes > 0) { "packages.maxModuleBytes must be positive" }
    }
}

/** The hidden WebView that runs esbuild. */
@Serializable
data class WebBuildHostConfig(
    /** Private https origin the build page loads from. Whittle answers every request on it; nothing reaches the network. */
    val domain: String,
    /** App assets folder with the build page, its scripts and esbuild. */
    val assetDirectory: String,
    /** The build page inside [assetDirectory]. */
    val page: String,
    /** Downloaded modules are cached here, under the app's cache folder, so rebuilds work offline. */
    val cacheDirectoryName: String,
    val cacheMaxBytes: Long,
    /** The bundler is shut down after this long without a build, to give its memory back. */
    val idleShutdownSeconds: Int,
) {
    init {
        require(cacheMaxBytes > 0 && idleShutdownSeconds > 0) { "host limits must be positive" }
    }
}
