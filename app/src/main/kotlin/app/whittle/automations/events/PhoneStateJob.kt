package app.whittle.automations.events

import android.app.job.JobParameters
import android.app.job.JobService
import app.whittle.WhittleApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Wakes Whittle on the charger and every [app.whittle.automations.EventsConfig.placeCheckMinutes] minutes, even from
 * a fresh process, and looks at the phone. check() arms again at the end, which covers a restart; arming first could cancel the charging job while it runs.
 */
class PhoneStateJob : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val watch = (application as WhittleApplication).phoneStateWatch
        running = (application as WhittleApplication).graph.appScope.launch {
            try {
                watch.check()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }
}
