package app.whittle.background

import app.whittle.linux.LinuxState
import app.whittle.linux.LinuxSteps
import app.whittle.tools.workspace.ProcessInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import app.whittle.MainActivity
import app.whittle.R
import app.whittle.tools.presentation.DecisionText
import app.whittle.updates.Release

/** What the ongoing notification shows about the running task. */
/** The running task as its notification shows it. [plan] is set when the agent wrote one (done, total, current step). */
data class RunProgress(val chatTitle: String?, val activity: String?, val startedAtMillis: Long, val plan: PlanProgress?)

data class PlanProgress(val done: Int, val total: Int, val current: String?)

/**
 * Whittle's notifications. While a task runs: one ongoing notification with what it's doing, a timer and
 * Stop (a Live Update on Android 16). When the user isn't looking: a note when it finishes or needs them.
 * On the lock screen only a generic line shows; the chat's words stay private.
 */
class RunNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RUNNING, context.getString(R.string.notify_channel_running), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notify_channel_running_body)
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.notify_channel_updates), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.notify_channel_updates_body)
                },
                NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.notify_channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.notify_channel_reminders_body)
                },
            ),
        )
    }

    fun running(progress: RunProgress): Notification {
        val title = progress.chatTitle ?: context.getString(R.string.notify_running_title)
        val activity = progress.activity ?: context.getString(R.string.status_thinking)
        val plan = progress.plan
        val text = if (plan != null) {
            context.getString(R.string.notify_running_plan, minOf(plan.done + 1, plan.total), plan.total, plan.current ?: activity)
        } else {
            activity
        }
        val builder = Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setWhen(progress.startedAtMillis)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_RUNNING, R.string.notify_running_public))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.status_stop), stopRun()).build())
        // Android 12+ holds a service's notification back for a few seconds; quick answers end first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
        // Only a planned task has real progress to show: one segment per step, filled as steps finish. It becomes
        // an Android 16 Live Update; without a plan the notification stays a quiet line that keeps the task alive.
        if (plan != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(List(plan.total) { Notification.ProgressStyle.Segment(1) })
                    .setProgress(plan.done),
            )
            // Live Updates arrived in Android 16's first update (36.1).
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    /** The task ended while the user was away. */
    fun finished(chatTitle: String?, failed: Boolean) = post(
        id = ID_FINISHED,
        title = context.getString(if (failed) R.string.notify_failed_title else R.string.notify_finished_title),
        text = chatTitle,
        publicText = if (failed) R.string.notify_failed_public else R.string.notify_finished_public,
    )

    /** A card is waiting for the user's choice. */
    fun needsYou(chatTitle: String?) = post(
        id = ID_NEEDS_YOU,
        title = context.getString(R.string.notify_needs_you_title),
        text = chatTitle,
        publicText = R.string.notify_needs_you_public,
    )

    /**
     * The approval card [key] waits while the user is away, in its own words. With [buttons], its Approve and Decline
     * buttons answer it as the card would, once the phone is unlocked (see [ApprovalActivity]); otherwise tapping opens
     * the chat. One per card, so several waiting cards are answered one by one.
     */
    fun approval(key: String, chatTitle: String?, text: DecisionText, buttons: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        // What the call would send or change, as the card shows it, so the answer isn't given blind.
        val details = text.payload?.let { payload -> text.payloadLabel?.let { "$it: $payload" } ?: payload }
        val builder = Notification.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(text.body, details).joinToString("\n\n")))
            .apply { chatTitle?.let(::setSubText) }
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_UPDATES, R.string.notify_approval_public))
        if (buttons) {
            builder.addAction(answerAction(key, text.decline, approve = false))
            builder.addAction(answerAction(key, text.approve, approve = true))
        }
        manager.notify(TAG_APPROVAL, key.hashCode(), builder.build())
    }

    /** The card [key] has its answer; its notification has nothing left to ask. */
    fun clearApproval(key: String) {
        manager.cancel(TAG_APPROVAL, key.hashCode())
    }

    /** The user is back: the notes about what happened while they were away have done their job. */
    fun clearUpdates() {
        manager.cancel(ID_FINISHED)
        manager.cancel(ID_NEEDS_YOU)
        manager.activeNotifications.filter { it.tag == TAG_APPROVAL }.forEach { manager.cancel(TAG_APPROVAL, it.id) }
    }

    /** A newer Whittle is out; tapping opens its release page, with what's new and the APK. */
    fun updateAvailable(release: Release) {
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            REQUEST_UPDATE,
            Intent(Intent.ACTION_VIEW, release.pageUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_update_title, release.version))
            .setContentText(context.getString(R.string.notify_update_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(ID_UPDATE, notification)
    }

    /** A reminder the user asked for; its words show only once the phone is unlocked. */
    fun reminder(id: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        val notification = Notification.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_REMINDERS, R.string.notify_reminder_public))
            .build()
        manager.notify(TAG_REMINDER, id.hashCode(), notification)
    }

    private fun post(id: Int, title: String, text: String?, publicText: Int) {
        if (!manager.areNotificationsEnabled()) return
        val notification = Notification.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { text?.let(::setContentText) }
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_UPDATES, publicText))
            .build()
        manager.notify(id, notification)
    }

    /**
     * Android's prompt to finish installing [appName] (null when it isn't known), when Whittle couldn't open it itself: Android
     * blocks it while Whittle isn't on screen. Tapping opens [prompt]. One per install session.
     */
    fun installPrompt(sessionId: Int, appName: String?, prompt: Intent) {
        if (!manager.areNotificationsEnabled()) return
        val title = appName?.let { context.getString(R.string.notify_install_prompt_title, it) }
            ?: context.getString(R.string.notify_install_prompt_title_unnamed)
        val open = PendingIntent.getActivity(context, sessionId, prompt, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_UPDATES, R.string.notify_install_prompt_public))
            .build()
        manager.notify(TAG_INSTALL_PROMPT, sessionId, notification)
    }

    /** The install session ended, so its prompt's notification has nothing left to open. */
    fun clearInstallPrompt(sessionId: Int) {
        manager.cancel(TAG_INSTALL_PROMPT, sessionId)
    }

    /** Servers the agent started, one line each, with Stop all; the notification that keeps them running. */
    fun processes(live: List<ProcessInfo>): Notification {
        val lines = live.map { info ->
            info.port?.takeIf { info.listening }?.let { context.getString(R.string.notify_process_live, info.name, it) }
                ?: context.getString(R.string.notify_process_running, info.name)
        }
        return Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notify_processes_title, live.size, live.size))
            .setContentText(lines.joinToString(" · "))
            .setStyle(Notification.InboxStyle().also { style -> lines.forEach(style::addLine) })
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_RUNNING, R.string.notify_processes_public))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.notify_stop_all), stopAllProcesses()).build())
            .build()
    }

    /** Linux setup while it downloads, unpacks and installs: a quiet line with real progress where there is some. */
    fun linuxSetup(state: LinuxState.SettingUp, imageName: String): Notification =
        Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_linux_title))
            .setContentText(listOfNotNull(LinuxSteps.label(context, state.step, done = false, imageName), LinuxSteps.detail(context, state)).joinToString(" · "))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_RUNNING, R.string.notify_linux_public))
            .setProgress(PERCENT, LinuxSteps.percent(state), state.totalBytes <= 0)
            .build()

    /** The app-building toolchain's one-time setup: a quiet line with a moving bar, as it has no count to show. */
    fun appToolchainSetup(): Notification =
        Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_app_toolchain_title))
            .setContentText(context.getString(R.string.notify_app_toolchain_text))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_RUNNING, R.string.notify_app_toolchain_public))
            .setProgress(0, 0, true)
            .build()

    private fun publicVersion(channel: String, text: Int): Notification =
        Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(text))
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopAllProcesses(): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_STOP_ALL,
        Intent(context, ProcessesService::class.java).setAction(ProcessesService.ACTION_STOP_ALL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens [ApprovalActivity], which Android shows only after the phone is unlocked; Android 12+ also asks before the tap counts. */
    private fun answerAction(key: String, label: String, approve: Boolean): Notification.Action {
        val intent = Intent(context, ApprovalActivity::class.java)
            .setAction(if (approve) ApprovalActivity.ACTION_APPROVE else ApprovalActivity.ACTION_DECLINE)
            .putExtra(ApprovalActivity.EXTRA_KEY, key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, label, open)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true) }
            .build()
    }

    private fun stopRun(): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_STOP,
        Intent(context, AgentRunService::class.java).setAction(AgentRunService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ID_RUNNING = 1
        const val ID_LINUX_SETUP = 4
        const val ID_PROCESSES = 5
        const val ID_APP_TOOLCHAIN = 6
        private const val ID_UPDATE = 7
        private const val PERCENT = 100
        private const val ID_FINISHED = 2
        private const val ID_NEEDS_YOU = 3
        private const val CHANNEL_RUNNING = "running_task"
        private const val CHANNEL_UPDATES = "task_updates"
        private const val CHANNEL_REMINDERS = "reminders"
        /** Reminders are told apart by tag, so their ids never meet the fixed ones above. */
        private const val TAG_REMINDER = "reminder"
        private const val TAG_INSTALL_PROMPT = "install_prompt"
        private const val TAG_APPROVAL = "approval"
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_STOP_ALL = 3
        private const val REQUEST_UPDATE = 4
    }
}
