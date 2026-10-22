package app.whittle.linux

import app.whittle.background.startInForeground

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.whittle.WhittleApplication
import app.whittle.background.RunNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps Linux's setup going while the user is in another app, with its progress in an ongoing notification. The work
 * itself is [LinuxWorkspace]'s; the service ends when it does.
 */
class LinuxSetupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as WhittleApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val linux = app.graph.linux
        val imageName = linux.image?.name.orEmpty()
        val first = linux.state.value as? LinuxState.SettingUp ?: LinuxState.SettingUp(LinuxSetupStep.Download)
        startInForeground(RunNotifications.ID_LINUX_SETUP, app.notifications.linuxSetup(first, imageName))
        val progress = scope.launch {
            linux.state.collectLatest { state ->
                if (state is LinuxState.SettingUp) app.notificationManager.notify(RunNotifications.ID_LINUX_SETUP, app.notifications.linuxSetup(state, imageName))
            }
        }
        scope.launch {
            when (intent?.action) {
                ACTION_REPAIR -> linux.repair()
                ACTION_RESET -> linux.reset()
                else -> linux.setUp()
            }
            progress.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        // Setup carries on from where it stopped next time, so a killed service needs no restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_SET_UP = "app.whittle.action.LINUX_SET_UP"
        private const val ACTION_REPAIR = "app.whittle.action.LINUX_REPAIR"
        private const val ACTION_RESET = "app.whittle.action.LINUX_RESET"

        fun setUp(context: Context) = start(context, ACTION_SET_UP)

        fun repair(context: Context) = start(context, ACTION_REPAIR)

        fun reset(context: Context) = start(context, ACTION_RESET)

        private fun start(context: Context, action: String) {
            context.startForegroundService(Intent(context, LinuxSetupService::class.java).setAction(action))
        }
    }
}
