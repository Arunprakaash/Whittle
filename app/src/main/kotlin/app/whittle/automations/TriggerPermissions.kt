package app.whittle.automations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** What Android makes the user allow before a trigger can work; asked for when an automation that needs it is saved. */
enum class TriggerPermission {
    SMS,
    NOTIFICATION_ACCESS,
    LOCATION,
    BACKGROUND_LOCATION,
    ;

    companion object
}

/**
 * What [trigger] needs, whether or not it's granted. A named Wi-Fi network needs location because Android hides the
 * name without it; any network, charging and intervals need nothing.
 */
fun TriggerPermission.Companion.needed(trigger: Trigger): List<TriggerPermission> = when (trigger) {
    is Trigger.Sms -> listOf(TriggerPermission.SMS)
    is Trigger.Notification -> listOf(TriggerPermission.NOTIFICATION_ACCESS)
    is Trigger.Place -> listOf(TriggerPermission.LOCATION, TriggerPermission.BACKGROUND_LOCATION)
    is Trigger.Wifi -> if (trigger.network != null) listOf(TriggerPermission.LOCATION) else emptyList()
    is Trigger.Charging, is Trigger.Every -> emptyList()
}

/** What [trigger] needs that isn't granted now. */
fun missing(context: Context, trigger: Trigger): List<TriggerPermission> =
    TriggerPermission.needed(trigger).filterNot { isGranted(context, it) }

private fun isGranted(context: Context, permission: TriggerPermission): Boolean = when (permission) {
    TriggerPermission.SMS -> granted(context, Manifest.permission.RECEIVE_SMS)
    TriggerPermission.NOTIFICATION_ACCESS -> context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    TriggerPermission.LOCATION -> granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    // Before Android 10 there is no separate background permission; at target 28 Android may also grant it with location.
    TriggerPermission.BACKGROUND_LOCATION ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
}

private fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
