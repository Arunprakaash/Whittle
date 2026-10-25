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

    /** Reading health data; granted on Health Connect's own screen, not as an Android runtime permission ([HealthConnect]). */
    val HEALTH = CapabilityId("health")

    /** Reading health data while Whittle is closed, for automations; also granted through Health Connect. */
    val HEALTH_BACKGROUND = CapabilityId("health_background")

    /** Capabilities Health Connect grants, which [isGranted] can't answer. */
    val HEALTH_CAPABILITIES = setOf(HEALTH, HEALTH_BACKGROUND)

    private val permissions = mapOf(
        CONTACTS to Manifest.permission.READ_CONTACTS,
        CALENDAR to Manifest.permission.READ_CALENDAR,
        CALENDAR_WRITE to Manifest.permission.WRITE_CALENDAR,
    )

    /** Whether Android or Health Connect knows how to grant [capability], so config may name it. */
    fun isKnown(capability: CapabilityId): Boolean = capability in permissions || capability in HEALTH_CAPABILITIES

    /** The Android permission to request for [capability], or null when it needs none. */
    fun permissionFor(capability: CapabilityId): String? = permissions[capability]

    /** Whether Android granted [capability]'s runtime permission; true when it needs none. Not for [HEALTH_CAPABILITIES]. */
    fun isGranted(context: Context, capability: CapabilityId): Boolean =
        permissionFor(capability)?.let { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true
}
