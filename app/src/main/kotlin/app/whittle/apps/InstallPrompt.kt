package app.whittle.apps

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * Shows Android's install prompt. Android 10 and later silently block an activity started while Whittle isn't on screen, with no
 * error, so then the prompt goes in a notification the user taps ([notify]). On screen it opens at once ([open], false when it
 * threw), and the notification is the fallback when it can't.
 */
fun showInstallPrompt(inForeground: Boolean, open: () -> Boolean, notify: () -> Unit) {
    if (inForeground && open()) return
    notify()
}

/**
 * Waits for an install session's result. [start] commits the session and hands the receiver a way to [deliver][InstallResult]
 * it. With no result in [timeout] (the prompt never showed, or the user left it), [stop] abandons the session and the install
 * counts as cancelled, so the card offers Install again. [stop] runs too when the wait itself is cancelled.
 */
suspend fun awaitInstallResult(timeout: Duration, start: ((InstallResult) -> Unit) -> Unit, stop: () -> Unit): InstallResult =
    withTimeoutOrNull(timeout) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { stop() }
            start { result -> if (continuation.isActive) continuation.resume(result) }
        }
    } ?: InstallResult.CANCELLED
