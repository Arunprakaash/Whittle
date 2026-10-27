package app.whittle.tools.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.whittle.tools.api.CapabilityId

/** Capabilities the phone tools and automations need, and the Android permission or settings screen behind each. */
object AndroidCapabilities {
    val CONTACTS = CapabilityId("contacts")
    val CALENDAR = CapabilityId("calendar")

    /** Adding events; reading them is [CALENDAR], asked for on its own. */
    val CALENDAR_WRITE = CapabilityId("calendar_write")

    /** Reading health data; granted on Health Connect's own screen, not as an Android runtime permission ([HealthConnect]). */
    val HEALTH = CapabilityId("health")

    /** Reading health data while Whittle is closed, for automations; also granted through Health Connect. */
    val HEALTH_BACKGROUND = CapabilityId("health_background")

    /** Capabilities Health Connect grants, which [isGranted] can't answer. */
    val HEALTH_CAPABILITIES = setOf(HEALTH, HEALTH_BACKGROUND)

    /** Texts as they arrive, for automations that start on one. */
    val SMS = CapabilityId("sms")

    /** Where the phone is: places, and the name of the Wi-Fi network, which Android hides without it. */
    val LOCATION = CapabilityId("location")

    /** Where the phone is while Whittle is closed, for automations that start at a place. Part of [LOCATION] before Android 10. */
    val LOCATION_BACKGROUND = CapabilityId("location_background")

    /** Other apps' notifications; granted on Android's notification access screen, not as a runtime permission. */
    val NOTIFICATION_ACCESS = CapabilityId("notification_access")

    private val permissions = mapOf(
        CONTACTS to Manifest.permission.READ_CONTACTS,
        CALENDAR to Manifest.permission.READ_CALENDAR,
        CALENDAR_WRITE to Manifest.permission.WRITE_CALENDAR,
        SMS to Manifest.permission.RECEIVE_SMS,
        LOCATION to Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** Capabilities granted on one of Android's settings screens, which the user opens and comes back from. */
    private val onSettingsScreen = setOf(NOTIFICATION_ACCESS)

    /** Whether Android or Health Connect knows how to grant [capability], so config may name it. */
    fun isKnown(capability: CapabilityId): Boolean =
        capability in permissions || capability in HEALTH_CAPABILITIES || capability == LOCATION_BACKGROUND || capability in onSettingsScreen

    /** The Android permission to request for [capability], or null when it needs none (or a settings screen grants it). */
    fun permissionFor(capability: CapabilityId): String? = when {
        capability == LOCATION_BACKGROUND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACCESS_BACKGROUND_LOCATION else null
        else -> permissions[capability]
    }

    /**
     * The settings screen where the user grants [capability] when no prompt can: notification access always, and
     * location all the time from Android 11, where the prompt no longer offers it. Null when a prompt is enough.
     */
    fun settingsIntentFor(context: Context, capability: CapabilityId): Intent? = when {
        capability == NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        capability == LOCATION_BACKGROUND && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        else -> null
    }

    /** Whether Android granted [capability]; true when it needs nothing. Not for [HEALTH_CAPABILITIES]. */
    fun isGranted(context: Context, capability: CapabilityId): Boolean = when (capability) {
        NOTIFICATION_ACCESS -> notificationAccessGranted(context)
        else -> permissionFor(capability)?.let { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true
    }

    /** Android keeps the allowed listeners as one setting, a colon-separated list of components (what NotificationManagerCompat reads too). */
    private fun notificationAccessGranted(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS).orEmpty().split(':')
            .any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }

    private const val ENABLED_LISTENERS = "enabled_notification_listeners"
}
