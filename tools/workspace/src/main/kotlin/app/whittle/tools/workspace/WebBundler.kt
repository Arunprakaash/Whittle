package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import java.io.File

/**
 * Bundles a project's entry scripts and everything they import into browser-ready files. The platform
 * supplies it: on Android, esbuild in a hidden WebView.
 */
fun interface WebBundler {
    /** A failure means the bundler couldn't run at all; errors in the project come back in [BundleResult.errors]. */
    suspend fun bundle(job: BundleJob): Outcome<BundleResult>
}

data class BundleJob(
    /** The workspace folder. */
    val root: File,
    /** Paths relative to [root], with "/" separators: the only files the build may read. */
    val files: List<String>,
    val entryPoints: List<EntryPoint>,
    /** Package name to version range, from the workspace's manifest. */
    val packageVersions: Map<String, String>,
)

/** [path] is bundled into `<outName>.js`, plus `<outName>.css` when it imports styles. */
data class EntryPoint(val path: String, val outName: String)

data class BundleResult(
    val succeeded: Boolean,
    /** Paths relative to the output folder. */
    val files: List<BundledFile>,
    val errors: List<String>,
    val warnings: List<String>,
)

class BundledFile(val path: String, val bytes: ByteArray)
