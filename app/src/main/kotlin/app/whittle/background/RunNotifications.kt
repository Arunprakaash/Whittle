package app.whittle.background

import app.whittle.linux.LinuxState
import app.whittle.linux.LinuxSteps
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.whittle.MainActivity
import app.whittle.R

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
            // Android holds a service's notification back for a few seconds; quick answers end first.
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setWhen(progress.startedAtMillis)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(CHANNEL_RUNNING, R.string.notify_running_public))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.status_stop), stopRun()).build())
        // Only a planned task has real progress to show: one segment per step, filled as steps finish. It becomes
        // an Android 16 Live Update; without a plan the notification stays a quiet line that keeps the task alive.
        if (plan != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(List(plan.total) { Notification.ProgressStyle.Segment(1) })
                    .setProgress(plan.done),
            )
            builder.setRequestPromotedOngoing(true)
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

    /** The user is back: the notes about what happened while they were away have done their job. */
    fun clearUpdates() {
        manager.cancel(ID_FINISHED)
        manager.cancel(ID_NEEDS_YOU)
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

    private fun stopRun(): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_STOP,
        Intent(context, AgentRunService::class.java).setAction(AgentRunService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ID_RUNNING = 1
        const val ID_LINUX_SETUP = 4
        private const val PERCENT = 100
        private const val ID_FINISHED = 2
        private const val ID_NEEDS_YOU = 3
        private const val CHANNEL_RUNNING = "running_task"
        private const val CHANNEL_UPDATES = "task_updates"
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
    }
}
