package app.whittle

import app.whittle.background.PlanProgress
import app.whittle.agent.PlanStatus
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import app.whittle.background.AgentRunService
import app.whittle.background.ProcessesService
import app.whittle.background.RunNotifications
import app.whittle.background.RunProgress
import app.whittle.chat.ChatEvent
import app.whittle.chat.ChatViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class WhittleApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    /**
     * One chat session for the app's whole life, not tied to a screen: a task keeps going when the user
     * leaves or swipes the app away, and the screen shows it live again when they come back.
     */
    val chat: ChatViewModel by lazy { ChatViewModel(graph) }

    val notifications: RunNotifications by lazy { RunNotifications(this) }
    val notificationManager: NotificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private val scope = MainScope()
    private var visibleActivities = 0

    /** Whether the user is looking at Whittle right now. */
    val inForeground: Boolean get() = visibleActivities > 0

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        registerActivityLifecycleCallbacks(VisibilityTracker())
        scope.launch {
            // Started with the task, while Whittle is on screen (Android refuses it once in the background).
            // Its notification waits a few seconds, so a quick answer never shows one.
            chat.state.map { it.running }.distinctUntilChanged().collect { running -> if (running && keepRunningInBackground()) startRunService() }
        }
        scope.launch {
            // Servers the agent started keep running while the user is elsewhere, under their own notification.
            graph.processes.processes.map { list -> list.any { it.live } }.distinctUntilChanged().collect { live ->
                if (live && keepRunningInBackground()) startService(ProcessesService::class.java)
            }
        }
        scope.launch {
            chat.events.collect { event ->
                if (inForeground) return@collect
                val title = runProgress()?.chatTitle ?: currentChatTitle()
                when (event) {
                    ChatEvent.NeedsYou -> notifications.needsYou(title)
                    is ChatEvent.Finished -> notifications.finished(title, event.failed)
                }
            }
        }
    }

    /** The running task as the ongoing notification shows it; null when nothing runs. */
    fun runProgress(): RunProgress? {
        val status = chat.state.value.status ?: return null
        val plan = status.plan?.let { steps ->
            PlanProgress(
                done = steps.count { it.status == PlanStatus.COMPLETED },
                total = steps.size,
                current = steps.firstOrNull { it.status == PlanStatus.IN_PROGRESS }?.content,
            )
        }
        return RunProgress(chatTitle = currentChatTitle(), activity = status.verb, startedAtMillis = status.startedAtMillis, plan = plan)
    }

    private fun currentChatTitle(): String? = chat.state.value.let { state -> state.chats.firstOrNull { it.id == state.currentChat }?.title }

    private fun keepRunningInBackground(): Boolean =
        graph.currentSettings.value?.keepRunningInBackground ?: graph.defaultKeepRunningInBackground

    private fun startRunService() = startService(AgentRunService::class.java)

    private fun startService(service: Class<*>) {
        try {
            startForegroundService(Intent(this, service))
        } catch (exception: IllegalStateException) {
            // Android 12+ refuses a start while Whittle is already in the background
            // (ForegroundServiceStartNotAllowedException, an IllegalStateException that older versions don't have);
            // the work then runs as before.
        }
    }

    private inner class VisibilityTracker : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibleActivities++
            notifications.clearUpdates()
        }

        override fun onActivityStopped(activity: Activity) {
            visibleActivities--
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
