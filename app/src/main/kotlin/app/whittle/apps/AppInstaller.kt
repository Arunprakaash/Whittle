package app.whittle.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.whittle.chat.ThreadItem
import app.whittle.core.common.Outcome
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

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
 * permissions and powerful declarations, no shared user id, and signed with the app's own key. [configured] is the
 * list of powerful declarations Whittle watches for.
 */
fun checkInstallable(card: ThreadItem.App, facts: ApkFacts, signerMatches: Boolean, configured: List<PowerfulDeclaration>): InstallCheck {
    val report = AppManifest.classify(facts, configured)
    val same = signerMatches &&
        !facts.sharedUserId &&
        facts.packageName == card.packageName &&
        facts.versionCode == card.versionCode.toLong() &&
        report.permissions.toSet() == card.permissions.toSet() &&
        report.powerful.toSet() == card.powerful.toSet()
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
fun verifyCopy(card: ThreadItem.App, apk: File, copy: File, expected: X509Certificate?, inspector: ApkInspector, configured: List<PowerfulDeclaration>): InstallCheck =
    try {
        apk.copyTo(copy, overwrite = true)
        if (expected == null) {
            InstallCheck.CHANGED
        } else {
            val verified = ApkVerifier.Builder(copy).setMinCheckedPlatformVersion(MIN_CHECKED_SDK).build().verify()
            val facts = (inspector.inspect(copy) as? Outcome.Success)?.value
            if (facts == null) InstallCheck.CHANGED else checkInstallable(card, facts, signerMatches(verified, expected), configured)
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
fun verifyForInstall(card: ThreadItem.App, apk: File, copy: File, signer: AppSigner, inspector: ApkInspector, configured: List<PowerfulDeclaration>): InstallCheck =
    verifyCopy(card, apk, copy, signer.existingCertificate(card.packageName), inspector, configured)

private const val MIN_CHECKED_SDK = 26

/**
 * Installs, updates and reads the state of the apps Whittle built. The user's tap is the only way anything reaches here.
 * The APK in the workspace can be replaced by processes the model started, so [install] works on a private copy that it
 * has verified itself.
 */
class AppInstaller(
    private val context: Context,
    private val signer: AppSigner,
    private val inspector: ApkInspector,
    private val configured: List<PowerfulDeclaration>,
) {
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

    /** Clears what a crash mid-install left behind: private APK copies and open install sessions. Safe to call at start. */
    fun cleanUp() {
        File(context.cacheDir, CACHE_DIRECTORY).listFiles()?.forEach { it.delete() }
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
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
            val prepared = withContext(Dispatchers.IO) { verifyForInstall(card, apk, copy, signer, inspector, configured) }
            if (prepared != InstallCheck.OK) return InstallResult.CHANGED
            return withContext(Dispatchers.IO) { commit(card, copy, onSession) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return InstallResult.FAILED
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { copy.delete() }
        }
    }

    private suspend fun commit(card: ThreadItem.App, copy: File, onSession: (Int) -> Unit): InstallResult {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(card.packageName) }
        val id = installer.createSession(params)
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
                suspendCancellableCoroutine { continuation ->
                    waiting[id] = { continuation.resume(it) }
                    continuation.invokeOnCancellation {
                        waiting.remove(id)
                        runCatching { installer.abandonSession(id) }
                    }
                    // The system fills in the status and the confirmation prompt, so the intent must stay mutable on 31+.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val receiver = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                    session.commit(PendingIntent.getBroadcast(context, id, receiver, flags).intentSender)
                }
            }
        } catch (e: CancellationException) {
            runCatching { installer.abandonSession(id) }
            throw e
        } catch (_: Exception) {
            waiting.remove(id)
            runCatching { installer.abandonSession(id) }
            InstallResult.FAILED
        }
    }

    companion object {
        private const val CACHE_DIRECTORY = "app-installs"
        private const val SESSION_FILE = "app.apk"
        private val waiting = ConcurrentHashMap<Int, (InstallResult) -> Unit>()

        internal fun deliver(sessionId: Int, result: InstallResult) {
            waiting.remove(sessionId)?.invoke(result)
        }
    }
}

/** Receives the installer's progress. Not exported: only the system, through the PendingIntent Whittle gave it, reaches it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val prompt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            try {
                prompt?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } catch (_: Exception) {
                // Android may refuse to open the prompt from here; the user can tap again.
                runCatching { context.packageManager.packageInstaller.abandonSession(session) }
                AppInstaller.deliver(session, InstallResult.FAILED)
            }
            return
        }
        AppInstaller.deliver(session, installResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
    }
}
