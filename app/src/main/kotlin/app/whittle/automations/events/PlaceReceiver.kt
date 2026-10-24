package app.whittle.automations.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import app.whittle.WhittleApplication
import kotlinx.coroutines.launch

/** Wakes when a proximity alert says the phone entered or left a place. Not exported; only Whittle's own alert sends to it. */
class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
        val app = context.applicationContext as WhittleApplication
        val pending = goAsync()
        app.graph.appScope.launch {
            try {
                app.phoneStateWatch.placeCrossed(name, entering)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "place"
    }
}
