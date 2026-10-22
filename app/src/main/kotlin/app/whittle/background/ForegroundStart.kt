package app.whittle.background

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Puts [this] in the foreground with [notification]. Whittle's services are special-use, a type that only exists
 * from Android 14; before that the type comes from the manifest, and before Android 10 there are no types at all.
 */
fun Service.startInForeground(id: Int, notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(id, notification)
    }
}
