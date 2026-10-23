package app.whittle.linux

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import app.whittle.BuildConfig
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.settings.TerminalDefaults
import app.whittle.tools.workspace.CommandLauncher
import app.whittle.tools.workspace.CommandRunner
import app.whittle.tools.workspace.LinuxFileSystem
import app.whittle.tools.workspace.ShellEnvironment
import app.whittle.tools.workspace.ShellTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/** Where Linux's setup is. */
sealed interface LinuxState {
    data object NotSetUp : LinuxState

    /** [from] is the first step this run does: a repair starts after the download and unpacking. */
    data class SettingUp(
        val step: LinuxSetupStep,
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
        val from: LinuxSetupStep = LinuxSetupStep.Download,
    ) : LinuxState

    data class Ready(val image: String) : LinuxState

    data class Failed(val step: LinuxSetupStep, val reason: String, val from: LinuxSetupStep = LinuxSetupStep.Download) : LinuxState
}

enum class LinuxSetupStep { Download, Unpack, Configure, BasePackages }

/** Where Linux's space went, in bytes. */
data class LinuxUsage(val base: Long, val added: Long, val caches: Long) {
    val total: Long get() = base + added + caches
}

/**
 * The Linux that commands run in: an Ubuntu root filesystem in the app's files folder, started with proot. Setup
 * downloads the image (resumable, checked against its SHA-256), unpacks it, writes the configured files and installs
 * the base packages; [launcher] then runs every command inside it, behind Whittle's sandbox (docs/linux-workspace.md).
 */
class LinuxWorkspace(
    private val context: Context,
    private val config: LinuxConfig,
    /** proot and tar, and the environment they run with. */
    private val programs: ShellTools,
    private val http: OkHttpClient,
    private val terminal: TerminalDefaults,
    killProcessGroup: ((processGroupId: Int) -> Unit)?,
    maxOutputChars: Int,
) {
    private val home = File(context.filesDir, config.directoryName)

    /** Never moved once unpacked: proot records hard links as links to device paths inside it. */
    private val rootfs = File(home, ROOTFS)
    private val configured = File(home, CONFIGURED_MARKER)
    private val installed = File(home, INSTALLED_MARKER)
    /** The base packages installed so far, one per line, so ones added to the config later can be topped up. */
    private val basePackages = File(home, PACKAGES_MARKER)
    /** What setup left on the phone, so Settings can tell what was added since. */
    private val baseSize = File(home, BASE_SIZE)
    private val downloads = File(home, DOWNLOADS)
    private val setupWorkspace = File(home, SETUP_WORKSPACE)
    private val lock = Mutex()

    /** The image for this phone's processor, or null when there's none. */
    val image: LinuxImage? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { config.images[it] }

    private val mutableState = MutableStateFlow(if (installed.isFile && configured.isFile) ready() else LinuxState.NotSetUp)
    val state: StateFlow<LinuxState> = mutableState.asStateFlow()

    /** Linux's root folder while it can run commands. */
    fun root(): File? = rootfs.takeIf { configured.isFile }

    /** What the file tools see: Linux paths, with the chat's folder at the workspace mount. */
    val fileSystem = LinuxFileSystem(::root, config.workspaceMount, config.deviceMounts)

    /** Where the shared workspace appears in Linux, such as /workspace. */
    val workspaceMount: String get() = config.workspaceMount

    val launcher = CommandLauncher { workspace, network -> command(workspace, network) }

    /** How workspace commands run: through [launcher], with proot and tar on the path. */
    val environment = ShellEnvironment(
        shell = terminal.shell,
        path = terminal.path,
        launcher = launcher,
        killProcessGroup = killProcessGroup,
        tools = programs,
    )

    private val runner = CommandRunner(environment, maxOutputChars)

    /** The first step of the run in progress, so its progress lists only the steps it does. Set under [lock]. */
    private var runFrom = LinuxSetupStep.Download

    /** Sets Linux up, carrying on from wherever an earlier attempt stopped. */
    suspend fun setUp(): Outcome<Unit> = lock.withLock {
        withContext(Dispatchers.IO) {
            runFrom = if (configured.isFile) LinuxSetupStep.BasePackages else LinuxSetupStep.Download
            val image = image ?: return@withContext fail(LinuxSetupStep.Download, "There's no Linux image for this phone's processor")
            if (!configured.isFile) {
                val archive = when (val downloaded = download(image)) {
                    is Outcome.Failure -> return@withContext fail(LinuxSetupStep.Download, downloaded.error.message)
                    is Outcome.Success -> downloaded.value
                }
                unpack(archive).failure()?.let { return@withContext fail(LinuxSetupStep.Unpack, it) }
                configure().failure()?.let { return@withContext fail(LinuxSetupStep.Configure, it) }
            }
            finishSetup(image)
        }
    }

    /** Writes the configured files again and reinstalls the base packages; files and added tools stay. */
    suspend fun repair(): Outcome<Unit> = lock.withLock {
        withContext(Dispatchers.IO) {
            runFrom = LinuxSetupStep.Configure
            val image = image ?: return@withContext fail(LinuxSetupStep.Configure, "There's no Linux image for this phone's processor")
            if (!rootfs.isDirectory) return@withContext fail(LinuxSetupStep.Configure, "Linux isn't unpacked; set it up again")
            configure().failure()?.let { return@withContext fail(LinuxSetupStep.Configure, it) }
            finishSetup(image)
        }
    }

    /**
     * Installs base packages added to the config since Linux was set up, quietly and once: Linux stays ready meanwhile,
     * and a failure (no network, say) leaves them for the next launch.
     */
    suspend fun topUpPackages(): Outcome<Unit> = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!installed.isFile || !configured.isFile) return@withContext Outcome.Success(Unit)
            // A Linux set up before the record existed has none, so it gets every base package; apt skips installed ones.
            val have = basePackages.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty().toSet()
            val missing = config.baseSetup.packages.filterNot { it in have }
            if (missing.isEmpty()) return@withContext Outcome.Success(Unit)
            if (!online()) return@withContext Outcome.Failure(HarnessError.Network(TOP_UP_OFFLINE))
            setupWorkspace.mkdirs()
            val before = sizeOf(rootfs) - cachesSize()
            installBase(config.baseSetup.install(missing)).also { outcome ->
                when (outcome) {
                    is Outcome.Failure -> Log.w(TAG, "Couldn't add base packages $missing: ${outcome.error.message}")
                    is Outcome.Success -> {
                        basePackages.writeText((have + missing).joinToString(separator = "\n", postfix = "\n"))
                        recordBaseGrowth(before)
                    }
                }
            }
        }
    }

    /** A fresh Linux: everything added to it goes, chat folders stay. */
    suspend fun reset(): Outcome<Unit> {
        remove()
        return setUp()
    }

    /** Frees the space; commands can't run until it's set up again. Chat folders stay. */
    suspend fun remove() = lock.withLock {
        withContext(Dispatchers.IO) {
            deleteTree(home)
            mutableState.value = LinuxState.NotSetUp
        }
    }

    /** Space Linux uses on the phone, links counted once and never followed. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { sizeOf(home) }

    /** Where Linux's space went: what setup left, what was added since, and caches. */
    suspend fun usage(): LinuxUsage = withContext(Dispatchers.IO) {
        // Linux's own folder only: the download and the markers beside it aren't part of it.
        val total = sizeOf(rootfs)
        val caches = cachesSize()
        val kept = (total - caches).coerceAtLeast(0)
        val base = (recordedBase() ?: kept).coerceAtMost(kept)
        LinuxUsage(base = base, added = kept - base, caches = caches)
    }

    /** Removes cached downloads; tools and files stay. */
    suspend fun clearCaches(): Outcome<Unit> {
        if (root() == null) return Outcome.Failure(HarnessError.NotFound(NOT_SET_UP))
        setupWorkspace.mkdirs()
        val result = when (val ran = runner.run(config.caches.clearCommand, setupWorkspace, config.caches.timeoutSeconds)) {
            is Outcome.Failure -> return ran
            is Outcome.Success -> ran.value
        }
        return if (result.exitCode == 0) Outcome.Success(Unit) else Outcome.Failure(HarnessError.Storage(result.stderr.lines().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"))
    }

    private fun cachesSize(): Long = config.caches.paths.sumOf { path -> sizeOf(File(rootfs, path.removePrefix(SEPARATOR))) }

    /** What setup left, caches aside; caches come and go, so they're measured when asked. */
    private fun recordedBase(): Long? = baseSize.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    private fun sizeOf(folder: File): Long {
        if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0L
        return Files.walk(folder.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
        }
    }

    private suspend fun finishSetup(image: LinuxImage): Outcome<Unit> {
        mutableState.value = settingUp(LinuxSetupStep.BasePackages)
        setupWorkspace.mkdirs()
        val before = sizeOf(rootfs) - cachesSize()
        val setup = config.baseSetup
        (listOf(setup.install(setup.packages)) + setup.commands).forEach { command ->
            installBase(command).failure()?.let { return fail(LinuxSetupStep.BasePackages, it) }
        }
        installed.writeText(image.version + "\n" + image.sha256)
        basePackages.writeText(setup.packages.joinToString(separator = "\n", postfix = "\n"))
        deleteTree(downloads)
        recordBaseGrowth(before)
        mutableState.value = ready()
        return Outcome.Success(Unit)
    }

    /** Runs one base setup command, with the network; fails with its last line of output. */
    private suspend fun installBase(command: String): Outcome<Unit> {
        val result = when (val ran = runner.run(command, setupWorkspace, config.baseSetup.timeoutSeconds, network = true)) {
            is Outcome.Failure -> return ran
            is Outcome.Success -> ran.value
        }
        if (result.timedOut || result.exitCode != 0) {
            return Outcome.Failure(HarnessError.Storage((result.stderr.ifBlank { result.stdout }).lines().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"))
        }
        return Outcome.Success(Unit)
    }

    /**
     * Counts what base setup just added as part of the base. A fresh Linux starts from the unpacked image; a repair or
     * top-up keeps what the user added out of the base and adds only what the base packages put there.
     */
    private fun recordBaseGrowth(before: Long) {
        val after = sizeOf(rootfs) - cachesSize()
        baseSize.writeText(((recordedBase() ?: before) + after - before).coerceAtLeast(0).toString())
    }

    /** The image, fetched once and kept until setup finishes; an interrupted download carries on where it stopped. */
    private suspend fun download(image: LinuxImage): Outcome<File> {
        downloads.mkdirs()
        val archive = File(downloads, image.sha256)
        if (archive.isFile) return Outcome.Success(archive)
        val partial = File(downloads, image.sha256 + PARTIAL)
        val digest = MessageDigest.getInstance(SHA_256)
        var have = partial.takeIf { it.isFile }?.length() ?: 0L
        if (have > 0) partial.inputStream().use { input -> digestAll(input, digest) }
        mutableState.value = settingUp(LinuxSetupStep.Download, have, image.sizeBytes)
        val request = Request.Builder().url(image.url).apply { if (have > 0) header(RANGE, "bytes=$have-") }.build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == HttpURLConnection.HTTP_PARTIAL -> Unit
                    response.isSuccessful -> {
                        // The server sent the whole file: start over.
                        have = 0
                        digest.reset()
                        partial.delete()
                    }
                    else -> return Outcome.Failure(HarnessError.Network("The download failed (HTTP ${response.code})"))
                }
                val body = response.body ?: return Outcome.Failure(HarnessError.Network("The download was empty"))
                java.io.FileOutputStream(partial, have > 0).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var reported = have
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            have += read
                            if (have - reported >= config.progressStepBytes) {
                                reported = have
                                mutableState.value = settingUp(LinuxSetupStep.Download, have, image.sizeBytes)
                            }
                        }
                    }
                }
            }
        } catch (exception: IOException) {
            return Outcome.Failure(HarnessError.Network("The download stopped: ${exception.message}. It carries on from here next time."))
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != image.sha256) {
            partial.delete()
            return Outcome.Failure(HarnessError.Network("The download didn't match its checksum, so it was discarded"))
        }
        if (!partial.renameTo(archive)) return Outcome.Failure(HarnessError.Storage("Couldn't keep the download"))
        return Outcome.Success(archive)
    }

    /** gunzip in Kotlin, tar under proot, so hard links and root-owned files unpack as the app. */
    private suspend fun unpack(archive: File): Outcome<Unit> {
        deleteTree(rootfs)
        configured.delete()
        rootfs.mkdirs()
        val total = archive.length()
        mutableState.value = settingUp(LinuxSetupStep.Unpack, 0, total)
        val arguments = listOf(PROOT) + config.prootOptions + listOf(TAR, "-C", rootfs.path, "-xf", "-") +
            config.unpackExclude.map { "--exclude=$it" }
        val process = startHost(arguments)
        return try {
            coroutineScope {
                val errors = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
                process.outputStream.use { output ->
                    GZIPInputStream(Counting(archive.inputStream().buffered()) { read ->
                        mutableState.value = settingUp(LinuxSetupStep.Unpack, read, total)
                    }).use { it.copyTo(output, BUFFER_BYTES) }
                }
                process.inputStream.close()
                val exit = process.waitFor()
                val error = errors.await()
                if (exit == 0) Outcome.Success(Unit) else Outcome.Failure(HarnessError.Storage(error.lines().lastOrNull { it.isNotBlank() } ?: "tar exited with $exit"))
            }
        } catch (exception: IOException) {
            Outcome.Failure(HarnessError.Storage("Unpacking stopped: ${exception.message}"))
        } finally {
            process.destroy()
        }
    }

    /** The configured files, the resolver, and the mount points proot binds onto. */
    private fun configure(): Outcome<Unit> {
        mutableState.value = settingUp(LinuxSetupStep.Configure)
        return try {
            config.files.forEach { (path, content) -> writeInside(path, content) }
            refreshDns(rootfs)
            linuxFile(config.workspaceMount).mkdirs()
            linuxFile(config.sandboxMount).apply { parentFile?.mkdirs() }.createNewFile()
            configured.writeText(image?.sha256.orEmpty())
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            Outcome.Failure(HarnessError.Storage("Couldn't configure Linux: ${exception.message}"))
        }
    }

    private fun command(workspace: File, network: Boolean): Outcome<List<String>> {
        val root = root() ?: return Outcome.Failure(HarnessError.NotFound(NOT_SET_UP))
        refreshDns(root)
        val sandbox = File(context.applicationInfo.nativeLibraryDir, BuildConfig.SANDBOX_PROGRAM)
        return Outcome.Success(
            buildList {
                // A new session around proot and everything it starts, so Stop and timeouts can end all of it.
                add(sandbox.path)
                add(SESSION_FLAG)
                add(END_OF_OPTIONS)
                add(PROOT)
                addAll(config.prootOptions)
                add(ROOT_OPTION)
                add(root.path)
                config.deviceMounts.forEach { mount ->
                    add(BIND_OPTION)
                    add(mount)
                }
                add(BIND_OPTION)
                add("${sandbox.path}:${config.sandboxMount}")
                add(BIND_OPTION)
                add("${workspace.path}:${config.workspaceMount}")
                add(WORKING_DIRECTORY_OPTION)
                add(config.workspaceMount)
                add(config.envProgram)
                add(CLEAR_ENVIRONMENT)
                config.environment.forEach { (name, value) -> add("$name=$value") }
                add(config.sandboxMount)
                if (network) add(NETWORK_FLAG)
                add(END_OF_OPTIONS)
                add(config.shell)
                add(SHELL_COMMAND)
            },
        )
    }

    /** Points Linux at the DNS servers of the phone's current network; Android has no resolver file of its own. */
    private fun refreshDns(root: File) {
        val servers = dnsServers().ifEmpty { config.dns.fallbackServers }
        val text = servers.joinToString(separator = "") { "nameserver $it\n" }
        try {
            val file = File(root, config.dns.file.removePrefix(SEPARATOR))
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.readText() != text) writeInside(config.dns.file, text, root)
        } catch (exception: IOException) {
            Log.w(TAG, "Couldn't update Linux's DNS servers", exception)
        }
    }

    private fun online(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null

    private fun dnsServers(): List<String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = connectivity.activeNetwork ?: return emptyList()
        // Link-local addresses carry a scope (fe80::1%wlan0) that a resolver file can't use.
        return connectivity.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.filterNot { SCOPE in it }
    }

    /**
     * Writes a file at a Linux path. The folders on the way resolve the way Linux would, and a link at the file
     * itself is replaced, never written through: Ubuntu's own links point at absolute paths.
     */
    private fun writeInside(path: String, content: String, root: File = rootfs) {
        val parentPath = path.substringBeforeLast(SEPARATOR).ifEmpty { SEPARATOR }
        val parent = when (val resolved = LinuxFileSystem({ root }, config.workspaceMount, config.deviceMounts).resolve(setupWorkspace, parentPath)) {
            is Outcome.Failure -> throw IOException(resolved.error.message)
            is Outcome.Success -> resolved.value
        }
        parent.mkdirs()
        val target = File(parent, path.substringAfterLast(SEPARATOR))
        Files.deleteIfExists(target.toPath())
        target.writeText(content)
    }

    /** A Linux path's place on the device, for setup's own folders. */
    private fun linuxFile(path: String): File = File(rootfs, path.removePrefix(SEPARATOR))

    /** Runs proot or tar from the APK, with the environment they need. */
    private suspend fun startHost(arguments: List<String>): Process {
        val setup = programs.setup()
        val line = arguments.joinToString(separator = " ") { "'" + it.replace("'", "'\\''") + "'" }
        return ProcessBuilder(terminal.shell, SHELL_COMMAND, "exec $line")
            .apply {
                environment().clear()
                environment()[PATH] = terminal.path
                environment().putAll(setup.environment)
            }
            .start()
    }

    private fun ready(): LinuxState = LinuxState.Ready(image?.name.orEmpty())

    private fun settingUp(step: LinuxSetupStep, doneBytes: Long = 0, totalBytes: Long = 0) =
        LinuxState.SettingUp(step, doneBytes, totalBytes, from = runFrom)

    private fun fail(step: LinuxSetupStep, reason: String): Outcome<Unit> {
        mutableState.value = LinuxState.Failed(step, reason, runFrom)
        return Outcome.Failure(HarnessError.Storage(reason))
    }

    private fun Outcome<Unit>.failure(): String? = (this as? Outcome.Failure)?.error?.message

    private fun digestAll(input: InputStream, digest: MessageDigest) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }

    /** Deletes a folder without following any link out of it. */
    private fun deleteTree(directory: File) {
        val start = directory.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(start).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /** Reports how many bytes have been read from [input]. */
    private class Counting(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) report(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) report(it.toLong()) }

        private fun report(read: Long) {
            count += read
            onRead(count)
        }
    }

    private companion object {
        const val TAG = "LinuxWorkspace"
        const val ROOTFS = "rootfs"
        const val DOWNLOADS = "download"
        const val SETUP_WORKSPACE = "setup"
        const val CONFIGURED_MARKER = ".configured"
        const val INSTALLED_MARKER = ".installed"
        const val PACKAGES_MARKER = ".base-packages"
        const val BASE_SIZE = ".base-size"
        const val PARTIAL = ".partial"
        const val SHA_256 = "SHA-256"
        const val RANGE = "Range"
        const val BUFFER_BYTES = 64 * 1024
        const val PROOT = "proot"
        const val TAR = "tar"
        const val ROOT_OPTION = "-r"
        const val BIND_OPTION = "-b"
        const val WORKING_DIRECTORY_OPTION = "-w"
        const val CLEAR_ENVIRONMENT = "-i"
        const val NETWORK_FLAG = "--network"
        const val SESSION_FLAG = "--session"
        const val END_OF_OPTIONS = "--"
        const val SHELL_COMMAND = "-c"
        const val PATH = "PATH"
        const val SEPARATOR = "/"
        const val SCOPE = "%"
        const val TOP_UP_OFFLINE = "No network to add base packages; they're added at the next launch"
        const val NOT_SET_UP = "Linux isn't set up on this phone yet, so commands can't run. " +
            "The user can set it up in Settings, under Linux workspace."
    }
}
