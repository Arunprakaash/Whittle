package app.whittle.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.whittle.WhittleApplication
import app.whittle.chat.ThreadItem
import app.whittle.core.common.EpochClock
import app.whittle.core.common.Outcome
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/** How an install ended, in the terms the card speaks. [CHANGED] means the APK no longer matched what the card showed, so nothing was installed. */
enum class InstallResult { INSTALLED, CANCELLED, KEY_CHANGED, BLOCKED, FAILED, CHANGED }

/**
 * Maps what [PackageInstaller] reports to what the card says. A conflict means the installed app is signed differently
 * (Whittle's key was lost), whatever words the message uses on this phone.
 */
fun installResult(status: Int, message: String?): InstallResult = when {
    status == PackageInstaller.STATUS_SUCCESS -> InstallResult.INSTALLED
    status == PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.CANCELLED
    status == PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallResult.BLOCKED
    status == PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallResult.KEY_CHANGED
    message?.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") == true -> InstallResult.KEY_CHANGED
    else -> InstallResult.FAILED
}

enum class InstallCheck { OK, CHANGED }

/**
 * Whether the APK that is about to be installed is still the one the card showed: same package and version, the same
 * permissions and powerful declarations, no shared user id, signed with the app's own key, and the [minSdk] and [targetSdk]
 * from config, as build_app requires. [configured] is the list of powerful declarations Whittle watches for. The SDKs are
 * checked again here because the signed APK sits in the workspace before build_app checks it, where a process could copy it.
 */
fun checkInstallable(card: ThreadItem.App, facts: ApkFacts, signerMatches: Boolean, configured: List<PowerfulDeclaration>, minSdk: Int, targetSdk: Int): InstallCheck {
    val report = AppManifest.classify(facts, configured)
    val same = signerMatches &&
        !facts.sharedUserId &&
        facts.packageName == card.packageName &&
        facts.versionCode == card.versionCode.toLong() &&
        facts.minSdk == minSdk &&
        facts.targetSdk == targetSdk &&
        report.permissions.toSet() == card.permissions.toSet() &&
        report.powerful.toSet() == card.powerful.toSet()
    return if (same) InstallCheck.OK else InstallCheck.CHANGED
}

/**
 * Whether a downloaded update may replace Whittle: [ownPackage], never older than [installedVersion] (a reinstall of the
 * same build is harmless), no shared user id, and signed with Whittle's own key. Android refuses a different key anyway;
 * checking first says why instead of a failed install.
 */
fun checkSelfUpdate(facts: ApkFacts, signerMatches: Boolean, ownPackage: String, installedVersion: Long): InstallCheck {
    val same = signerMatches && !facts.sharedUserId && facts.packageName == ownPackage && facts.versionCode >= installedVersion
    return if (same) InstallCheck.OK else InstallCheck.CHANGED
}

/** Whether [result] says the APK is validly signed, with the v2 or v3 scheme, by exactly [expected] and nobody else. */
fun signerMatches(result: ApkVerifier.Result, expected: X509Certificate): Boolean =
    result.isVerified && (result.isVerifiedUsingV2Scheme || result.isVerifiedUsingV3Scheme) &&
        result.signerCertificates.size == 1 && result.signerCertificates.single().encoded.contentEquals(expected.encoded)

/**
 * Copies [apk] to [copy], checks the copy's signature and facts against [card] and says whether it may be installed. A file
 * that can't be read or parsed is a changed file, not a crash.
 */
fun verifyCopy(
    card: ThreadItem.App,
    apk: File,
    copy: File,
    expected: X509Certificate?,
    inspector: ApkInspector,
    configured: List<PowerfulDeclaration>,
    minSdk: Int,
    targetSdk: Int,
): InstallCheck =
    try {
        apk.copyTo(copy, overwrite = true)
        if (expected == null) {
            InstallCheck.CHANGED
        } else {
            // Signatures are checked as every Android the app installs on would check them, from its minSdk up.
            val verified = ApkVerifier.Builder(copy).setMinCheckedPlatformVersion(minSdk).build().verify()
            val facts = (inspector.inspect(copy) as? Outcome.Success)?.value
            if (facts == null) InstallCheck.CHANGED else checkInstallable(card, facts, signerMatches(verified, expected), configured, minSdk, targetSdk)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApkFormatException) {
        InstallCheck.CHANGED
    } catch (_: GeneralSecurityException) {
        InstallCheck.CHANGED
    } catch (_: IllegalStateException) {
        InstallCheck.CHANGED
    } catch (_: IOException) {
        InstallCheck.CHANGED
    }

/**
 * [verifyCopy] against the key Whittle already holds for the card's package. It never makes a key: without one the APK
 * can't be Whittle's build, so it is a changed file.
 */
fun verifyForInstall(
    card: ThreadItem.App,
    apk: File,
    copy: File,
    signer: AppSigner,
    inspector: ApkInspector,
    configured: List<PowerfulDeclaration>,
    minSdk: Int,
    targetSdk: Int,
): InstallCheck = verifyCopy(card, apk, copy, signer.existingCertificate(card.packageName), inspector, configured, minSdk, targetSdk)

/**
 * Installs, updates and reads the state of the apps Whittle built. The user's tap is the only way anything reaches here.
 * The APK in the workspace can be replaced by processes the model started, so [install] works on a private copy that it
 * has verified itself. An install whose prompt gets no answer in [NativeAppsConfig.installPromptTimeoutMinutes] is cancelled;
 * [clearPrompt] takes down the prompt's notification when an install ends.
 */
class AppInstaller(
    private val context: Context,
    private val signer: AppSigner,
    private val inspector: ApkInspector,
    private val config: NativeAppsConfig,
    clock: EpochClock,
    private val clearPrompt: (Int) -> Unit,
) {
    private val configured = config.powerful

    private val sessions = InstallSessions(
        File(context.filesDir, InstallSessions.FILE_NAME),
        clock,
        config.staleInstallSessionMinutes.minutes.inWholeMilliseconds,
        object : SessionLister {
            private val installer get() = context.packageManager.packageInstaller

            override fun live(): Set<Int> = liveSessions()

            override fun abandon(id: Int) {
                runCatching { installer.abandonSession(id) }
            }
        },
        clearPrompt,
    )

    /** Asks PackageManager, so call it off the main thread. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The version code installed for [packageName], or null when it isn't installed. */
    fun installedVersion(packageName: String): Int? = installedInfo(packageName, 0)?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
    }

    /** The powerful declarations the installed app has, read the way a built APK's are; null when it isn't installed. */
    suspend fun installedPowerful(packageName: String): List<PowerfulDeclaration>? = withContext(Dispatchers.IO) {
        installedInfo(packageName, FACT_FLAGS)?.let { AppManifest.classify(apkFactsOf(it), configured).powerful }
    }

    /**
     * Clears what a crash mid-install left behind: stale or unknown install sessions ([InstallSessions]) and private APK copies.
     * A young session is kept, since the user may be confirming it. No session uses a copy (each session holds its own once
     * written), so every copy goes. Call it at start, before any install.
     */
    fun cleanUp() {
        File(context.cacheDir, CACHE_DIRECTORY).listFiles()?.forEach { it.delete() }
        sessions.cleanUp()
    }

    /** The ids of this app's install sessions that are still open. */
    fun liveSessions(): Set<Int> = context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()

    private fun installedInfo(packageName: String, flags: Int) =
        try {
            context.packageManager.getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** Verifies a private copy of [apk] against [card], then installs that same copy; the user confirms on Android's own prompt. */
    suspend fun install(card: ThreadItem.App, apk: File, onSession: (Int) -> Unit = {}): InstallResult {
        val copy = File(File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }, "${UUID.randomUUID()}.apk")
        try {
            val prepared = withContext(Dispatchers.IO) { verifyForInstall(card, apk, copy, signer, inspector, configured, config.minSdk, config.targetSdk) }
            if (prepared != InstallCheck.OK) return InstallResult.CHANGED
            return withContext(Dispatchers.IO) { commit(card.packageName, card.name, copy, onSession) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return InstallResult.FAILED
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { copy.delete() }
        }
    }

    /**
     * Installs a new version of Whittle itself from [apk], a download already checked against GitHub's digest, once it
     * passes [checkSelfUpdate]. The user confirms on Android's prompt; once they do, Android replaces Whittle and this
     * process ends. [appName] names the install in the prompt's notification.
     */
    suspend fun installUpdate(apk: File, appName: String): InstallResult = try {
        val own = context.packageName
        val ok = withContext(Dispatchers.IO) {
            val expected = signerOf(context.packageManager, own)
            val verified = expected != null &&
                signerMatches(ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(context.applicationInfo.minSdkVersion).build().verify(), expected)
            val facts = (inspector.inspect(apk) as? Outcome.Success)?.value
            val installed = installedVersion(own)?.toLong() ?: 0L
            facts != null && checkSelfUpdate(facts, verified, own, installed) == InstallCheck.OK
        }
        if (!ok) InstallResult.CHANGED else withContext(Dispatchers.IO) { commit(own, appName, apk) {} }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        InstallResult.FAILED
    }

    private suspend fun commit(packageName: String, appName: String, copy: File, onSession: (Int) -> Unit): InstallResult {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(packageName) }
        val id = installer.createSession(params)
        sessions.started(id)
        onSession(id)
        return try {
            installer.openSession(id).use { session ->
                copy.inputStream().use { input ->
                    session.openWrite(SESSION_FILE, 0, copy.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // The session holds its own copy now.
                copy.delete()
                awaitInstallResult(
                    config.installPromptTimeoutMinutes.minutes,
                    start = { deliver ->
                        waiting[id] = Waiting(appName, deliver)
                        // The system fills in the status and the confirmation prompt, so the intent must stay mutable on 31+.
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                        val receiver = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                        session.commit(PendingIntent.getBroadcast(context, id, receiver, flags).intentSender)
                    },
                    // Timed out or cancelled: nobody waits for the result any more, and the session goes.
                    stop = {
                        waiting.remove(id)
                        runCatching { installer.abandonSession(id) }
                    },
                )
            }
        } catch (e: CancellationException) {
            runCatching { installer.abandonSession(id) }
            throw e
        } catch (_: Exception) {
            waiting.remove(id)
            runCatching { installer.abandonSession(id) }
            InstallResult.FAILED
        } finally {
            sessions.ended(id)
            clearPrompt(id)
        }
    }

    companion object {
        private const val CACHE_DIRECTORY = "app-installs"
        private const val SESSION_FILE = "app.apk"
        private val waiting = ConcurrentHashMap<Int, Waiting>()

        internal fun deliver(sessionId: Int, result: InstallResult) {
            waiting.remove(sessionId)?.deliver?.invoke(result)
        }

        /** The name of the app session [sessionId] installs, while an install waits for it; for the prompt's notification. */
        internal fun appName(sessionId: Int): String? = waiting[sessionId]?.appName
    }

    /** An install waiting for its session's result. */
    private class Waiting(val appName: String, val deliver: (InstallResult) -> Unit)
}

/**
 * Receives the installer's progress. Not exported: only the system, through the PendingIntent Whittle gave it, reaches it. The
 * prompt opens at once while Whittle is on screen, and otherwise waits in a notification ([showInstallPrompt]); an install
 * the user never answers is cancelled after its timeout.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val application = context.applicationContext as? WhittleApplication
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val prompt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            } ?: return
            prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            showInstallPrompt(
                inForeground = application?.inForeground == true,
                open = {
                    try {
                        context.startActivity(prompt)
                        true
                    } catch (_: Exception) {
                        false
                    }
                },
                notify = { application?.notifications?.installPrompt(session, AppInstaller.appName(session), prompt) },
            )
            return
        }
        application?.notifications?.clearInstallPrompt(session)
        AppInstaller.deliver(session, installResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
    }
}
