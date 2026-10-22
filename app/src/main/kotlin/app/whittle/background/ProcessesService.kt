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
 * Keeps Whittle, and so the servers the agent started in Linux, running while the user is elsewhere. The processes
 * are the [app.whittle.tools.workspace.ProcessManager]'s; this lists them in an ongoing notification with Stop all, and
 * ends when none is left.
 */
class ProcessesService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as WhittleApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val processes = app.graph.processes
        if (intent?.action == ACTION_STOP_ALL) app.graph.appScope.launch { processes.stopAll() }
        val live = processes.processes.value.filter { it.live }
        if (live.isEmpty() && intent?.action != ACTION_STOP_ALL) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(RunNotifications.ID_PROCESSES, app.notifications.processes(live), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        scope.launch {
            processes.processes.map { list -> list.filter { it.live } }.distinctUntilChanged().collect { current ->
                if (current.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    app.notificationManager.notify(RunNotifications.ID_PROCESSES, app.notifications.processes(current))
                }
            }
        }
        // The processes end with the app; nothing to bring back if Android ends the service.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_ALL = "app.whittle.action.STOP_ALL_PROCESSES"
    }
}
