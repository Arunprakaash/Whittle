package app.whittle.export

import android.content.Context
import app.whittle.BuildConfig
import app.whittle.artifacts.ArtifactRef
import app.whittle.core.common.getOrNull
import app.whittle.settings.ExportDefaults
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Result of turning an app into an installable Android app. */
sealed interface ExportResult {
    /** Android's installer is taking over; it asks the user to confirm. */
    data object Installing : ExportResult

    /** The user must first let Whittle install apps, once, in Android's settings. */
    data object NeedsPermission : ExportResult

    data class Failed(val reason: String) : ExportResult
}

/**
 * Turns an app the agent built into its own Android app: the shell APK with the app's files, a package of its
 * own (stable for the same app, so a new export updates it), its name and a generated icon, signed on the phone.
 */
class AppExporter(
    private val context: Context,
    private val workspaces: Workspaces,
    private val config: ExportDefaults,
    private val minSdk: Int,
) {
    private val packager = AppPackager(BuildConfig.EXPORT_PACKAGE_PLACEHOLDER, BuildConfig.EXPORT_LABEL_PLACEHOLDER)
    private val signer = AppSigner(config.signingKeyAlias, config.certificateSubject, config.certificateValidityYears, minSdk)
    val installer = AppInstaller(context)

    suspend fun install(ref: ArtifactRef): ExportResult = withContext(Dispatchers.IO) {
        if (!installer.allowed()) return@withContext ExportResult.NeedsPermission
        val page = workspaces.resolve(WorkspaceId(ref.workspace), ref.path).getOrNull()?.takeIf { it.isFile }
            ?: return@withContext ExportResult.Failed("the app's files are no longer on this phone")
        val files = appFiles(page)
        if (files.values.sumOf { it.length() } > config.maxAppBytes) return@withContext ExportResult.Failed("the app is too large to install")
        val identity = digest(ref.workspace + IDENTITY_SEPARATOR + ref.path)
        val bundle = AppBundle(
            packageName = packageName(ref.title, identity),
            label = label(ref.title),
            iconPng = AppIcon.png(ref.title, identity.first().toInt(), config.iconSizePx, config.iconColors),
            files = files,
        )
        val folder = File(context.cacheDir, config.workDirectoryName).apply { mkdirs() }
        val unsigned = File(folder, UNSIGNED)
        val signed = File(folder, SIGNED)
        try {
            context.assets.open(BuildConfig.EXPORT_SHELL_ASSET).use { shell -> unsigned.outputStream().use { packager.pack(shell, bundle, it) } }
            signer.sign(unsigned, signed)
            installer.install(signed, bundle.packageName)
            ExportResult.Installing
        } catch (exception: Exception) {
            ExportResult.Failed(exception.message ?: exception.javaClass.simpleName)
        } finally {
            unsigned.delete()
        }
    }

    /**
     * Everything beside the built page, with the page itself also served as the app's start page. Hidden files
     * and folders stay out, such as the tool outputs Whittle keeps in the workspace.
     */
    private fun appFiles(page: File): Map<String, File> {
        val root = page.parentFile ?: return emptyMap()
        val files = root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(HIDDEN_PREFIX) }
            .filter { it.isFile && !it.name.startsWith(HIDDEN_PREFIX) }
            .associateBy { it.relativeTo(root).invariantSeparatorsPath }
        return files + (START_PAGE to page)
    }

    /** A home-screen name: the title before any subtitle ("Habits — a tracker"), cut at a word if still long. */
    private fun label(title: String): String {
        val name = config.labelSeparators.fold(title) { text, separator -> text.substringBefore(separator) }.trim().ifEmpty { title.trim() }
        if (name.length <= config.maxLabelChars) return name
        val cut = name.take(config.maxLabelChars + 1)
        return cut.substringBeforeLast(' ', cut.take(config.maxLabelChars)).trim()
    }

    private fun packageName(title: String, identity: ByteArray): String {
        val name = title.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.dropWhile { it.isDigit() }.take(config.maxNameChars).ifEmpty { FALLBACK_NAME }
        val suffix = identity.take(ID_BYTES).joinToString("") { "%02x".format(it) }
        return "${config.packagePrefix}.$name.$ID_PREFIX$suffix"
    }

    private fun digest(text: String): ByteArray = MessageDigest.getInstance(DIGEST).digest(text.toByteArray())

    private companion object {
        const val START_PAGE = "index.html"
        const val HIDDEN_PREFIX = "."
        const val IDENTITY_SEPARATOR = "/"
        const val FALLBACK_NAME = "app"
        const val ID_PREFIX = "a"
        const val ID_BYTES = 4
        const val DIGEST = "SHA-256"
        const val UNSIGNED = "app-unsigned.apk"
        const val SIGNED = "app.apk"
    }
}
