package app.whittle.quickstart

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService

/** Whittle in Quick Settings: a tap opens a new chat, after the phone is unlocked. */
class WhittleTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::open) else open()
    }

    private fun open() {
        val intent = QuickStart.NEW_CHAT.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            // Before Android 14 this is the only way, and the one lint suggests doesn't exist there.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
