package app.whittle.linux

import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable

/** The Linux that commands run in: which image, how it's started, and what setup adds (docs/linux-workspace.md). */
@Serializable
data class LinuxConfig(
    override val schemaVersion: Int,
    /** Under the app's files folder: the root filesystem, its download and markers. */
    val directoryName: String,
    /** ABI to the image for it. */
    val images: Map<String, LinuxImage>,
    /** tar patterns left out when unpacking: device files Android doesn't let an app create. */
    val unpackExclude: List<String>,
    val prootOptions: List<String>,
    /** Live views of the device, mounted for commands; the file tools refuse them. */
    val deviceMounts: List<String>,
    /** Where the chat's folder appears inside Linux; commands start there. */
    val workspaceMount: String,
    /** Where the sandbox program appears inside Linux. */
    val sandboxMount: String,
    val shell: String,
    /** Starts the command with only [environment]: Linux's own `env`. */
    val envProgram: String,
    /** A command's whole environment inside Linux; nothing of the app's own environment reaches it. */
    val environment: Map<String, String>,
    /** Linux path to content, written at setup and on repair. */
    val files: Map<String, String>,
    val dns: LinuxDns,
    val baseSetup: LinuxBaseSetup,
    /** How often the download reports progress. */
    val progressStepBytes: Long,
) : VersionedConfig {
    init {
        require(images.isNotEmpty()) { "images must name at least one ABI" }
        require(workspaceMount.startsWith("/") && sandboxMount.startsWith("/")) { "Mounts must be absolute Linux paths" }
        require(progressStepBytes > 0) { "progressStepBytes must be positive" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("linux-workspace.json")
    }
}

@Serializable
data class LinuxImage(
    /** Shown to the user. */
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Roughly what setup leaves on the phone, base packages included; shown before setting up. */
    val setupSizeBytes: Long,
)

@Serializable
data class LinuxDns(
    /** Linux path of the resolver file, rewritten from the phone's current network before commands run. */
    val file: String,
    /** Used when the phone doesn't report its network's DNS servers. */
    val fallbackServers: List<String>,
)

@Serializable
data class LinuxBaseSetup(
    /** Run once at setup, with the network, to install what most tasks need. */
    val command: String,
    val timeoutSeconds: Int,
)
