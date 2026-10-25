package app.whittle.updates

import app.whittle.apps.InstallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where an update Whittle installs itself stands, for the Settings row. */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** Android won't let Whittle install apps yet; the user allows it, then taps Update again. */
    data class NeedsPermission(val version: String) : UpdateState

    data class Downloading(val version: String, val percent: Int) : UpdateState

    /** Downloaded and checked; Android's prompt is up or about to be. */
    data class Installing(val version: String) : UpdateState

    data class Failed(val version: String, val reason: UpdateFailure) : UpdateState
}

/**
 * Why an update didn't install: no connection, a file that wasn't GitHub's, an APK that isn't this Whittle's, Android
 * refused it, or the release has no APK Whittle can install (its page still has one).
 */
enum class UpdateFailure { NETWORK, MISMATCH, NOT_WHITTLE, INSTALL, NO_APK }

/** What [SelfUpdate] needs from the phone, so the flow itself is plain code. */
interface SelfUpdateHost {
    /** Whether Android lets Whittle install apps; asks PackageManager, so it's called off the main thread. */
    fun canInstall(): Boolean

    /** Hands a downloaded, digest-checked APK to Android's installer; returns once the install ends or is cancelled. */
    suspend fun install(apk: File): InstallResult

    /** Shows the update's progress in its notification: a percentage while downloading, null while installing. */
    fun progress(version: String, percent: Int?)

    /** Takes the progress notification down. */
    fun done()
}

/**
 * Updates Whittle from inside the app: downloads the latest release's APK, keeps it only when it matches GitHub's SHA-256,
 * and installs it through Android's installer, which asks the user and refuses an APK not signed like this one. One update
 * runs at a time; [state] follows it.
 */
class SelfUpdate(
    private val check: UpdateCheck,
    private val download: suspend (ReleaseApk, File, (Long, Long) -> Unit) -> DownloadResult,
    private val directory: File,
    private val host: SelfUpdateHost,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    /** Starts updating to the release the check found, unless an update is already under way. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Deletes downloads an earlier run left (the app was closed mid-download, or replaced by the update). */
    fun cleanUp() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private suspend fun run() {
        // A release saved before APKs were recorded has none: ask GitHub again first.
        val release = check.available.value?.takeIf { it.apk != null }
            ?: run { check.checkIfDue(force = true); check.available.value }
            ?: return
        val version = release.version
        val apk = release.apk ?: return fail(version, UpdateFailure.NO_APK)
        if (!host.canInstall()) {
            _state.value = UpdateState.NeedsPermission(version)
            return
        }
        val file = File(directory, apk.url.substringAfterLast('/'))
        _state.value = UpdateState.Downloading(version, 0)
        host.progress(version, 0)
        var shown = 0
        val downloaded = download(apk, file) { done, total ->
            val percent = if (total > 0) (done * PERCENT / total).toInt().coerceIn(0, PERCENT) else 0
            // Only whole-percent steps reach the screen and the notification.
            if (percent != shown) {
                shown = percent
                _state.value = UpdateState.Downloading(version, percent)
                host.progress(version, percent)
            }
        }
        if (downloaded != DownloadResult.OK) {
            return fail(version, if (downloaded == DownloadResult.MISMATCH) UpdateFailure.MISMATCH else UpdateFailure.NETWORK)
        }
        _state.value = UpdateState.Installing(version)
        host.progress(version, null)
        val installed = host.install(file)
        file.delete()
        host.done()
        // Once the user confirms, Android replaces Whittle and this process ends; a cancelled prompt leaves Update to tap again.
        _state.value = when (installed) {
            InstallResult.INSTALLED, InstallResult.CANCELLED -> UpdateState.Idle
            InstallResult.KEY_CHANGED, InstallResult.CHANGED -> UpdateState.Failed(version, UpdateFailure.NOT_WHITTLE)
            InstallResult.BLOCKED, InstallResult.FAILED -> UpdateState.Failed(version, UpdateFailure.INSTALL)
        }
    }

    private fun fail(version: String, reason: UpdateFailure) {
        host.done()
        _state.value = UpdateState.Failed(version, reason)
    }

    private companion object {
        const val PERCENT = 100
    }
}
