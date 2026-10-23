package app.whittle.apps

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import app.whittle.WhittleApplication
import app.whittle.background.RunNotifications
import app.whittle.background.startInForeground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app-building toolchain's one-time setup going while the user is in another app, with an ongoing
 * notification. The work is [AppToolchain]'s; the service ends when it does.
 */
class AppToolchainService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as WhittleApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(RunNotifications.ID_APP_TOOLCHAIN, app.notifications.appToolchainSetup())
        scope.launch {
            try {
                app.graph.appToolchain.install()
                (app.graph.appToolchain.state.value as? ToolchainState.Failed)?.let { Log.w(TAG, "App building setup failed: ${it.reason}") }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        // A killed setup is cheap to start again from the card or Settings, so no restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LinuxWorkspace"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AppToolchainService::class.java))
        }
    }
}
