package app.whittle.tools.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import app.whittle.tools.api.CapabilityId

/** Capabilities the phone tools need, and the Android runtime permission behind each. */
object AndroidCapabilities {
    val CONTACTS = CapabilityId("contacts")
    val CALENDAR = CapabilityId("calendar")

    /** Adding events; reading them is [CALENDAR], asked for on its own. */
    val CALENDAR_WRITE = CapabilityId("calendar_write")

    private val permissions = mapOf(
        CONTACTS to Manifest.permission.READ_CONTACTS,
        CALENDAR to Manifest.permission.READ_CALENDAR,
        CALENDAR_WRITE to Manifest.permission.WRITE_CALENDAR,
    )

    /** The Android permission to request for [capability], or null when it needs none. */
    fun permissionFor(capability: CapabilityId): String? = permissions[capability]

    fun isGranted(context: Context, capability: CapabilityId): Boolean =
        permissionFor(capability)?.let { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true
}
