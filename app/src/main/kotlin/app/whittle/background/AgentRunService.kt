package app.whittle.background

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.whittle.WhittleApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Whittle running while a task works and the user is elsewhere. It holds no run itself: the app's chat
 * session does. It mirrors the session's status in the ongoing notification and ends when the task does.
 */
class AgentRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as WhittleApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.chat.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val progress = app.runProgress() ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(RunNotifications.ID_RUNNING, app.notifications.running(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        scope.launch {
            app.chat.state.map { app.runProgress() }.distinctUntilChanged().collect { current ->
                if (current == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    app.notificationManager.notify(RunNotifications.ID_RUNNING, app.notifications.running(current))
                }
            }
        }
        // A task can't resume in a new process this way; if Android ends the service, the chat offers Continue.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.whittle.action.STOP_RUN"
    }
}
