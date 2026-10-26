package app.whittle.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.whittle.WhittleApplication
import app.whittle.linux.ComputerAccessState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps Whittle, and so the SSH server in Linux, running while computers may sign in, behind an ongoing notification
 * with Turn off. It stays through a restart at a new Wi-Fi address and ends when computer access is off or stopped.
 */
class ComputerAccessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as WhittleApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val access = app.graph.computerAccess
        if (intent?.action == ACTION_TURN_OFF) access.turnOff()
        val on = access.state.value as? ComputerAccessState.On
        // Started with startForegroundService, it must go foreground before anything else, even to stop at once.
        startInForeground(RunNotifications.ID_COMPUTER_ACCESS, on?.let(::notification) ?: app.notifications.computerAccessStopping())
        if (on == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            access.state.collect { state ->
                when (state) {
                    is ComputerAccessState.On ->
                        app.notificationManager.notify(RunNotifications.ID_COMPUTER_ACCESS, notification(state))
                    ComputerAccessState.Off, is ComputerAccessState.Failed -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    // Moving to a new address; the notification stays until it's back.
                    ComputerAccessState.Installing, ComputerAccessState.Starting -> Unit
                }
            }
        }
        // The server ends with the app; nothing to bring back if Android ends the service.
        return START_NOT_STICKY
    }

    private fun notification(on: ComputerAccessState.On) = app.notifications.computerAccess(
        signIn = app.graph.computerAccess.config.signIn(on.address),
        overTailscale = on.wifi == null,
        anywhere = on.tailscale?.takeIf { on.wifi != null },
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TURN_OFF = "app.whittle.action.TURN_OFF_COMPUTER_ACCESS"

        /** Starts the service once the server is up; it stops by itself when access goes off. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ComputerAccessService::class.java))
        }
    }
}
