package app.whittle.tools.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
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

    /** Files in shared storage (Downloads, photos, documents). From Android 11 it is "all files access", a settings page, not a prompt. */
    val STORAGE = CapabilityId("storage")

    private const val ALL_FILES_API = Build.VERSION_CODES.R

    private val permissions = mapOf(
        CONTACTS to Manifest.permission.READ_CONTACTS,
        CALENDAR to Manifest.permission.READ_CALENDAR,
        CALENDAR_WRITE to Manifest.permission.WRITE_CALENDAR,
    )

    /** Whether Android or Health Connect knows how to grant [capability], so config may name it. */
    fun isKnown(capability: CapabilityId): Boolean = capability in permissions || capability in HEALTH_CAPABILITIES || capability == STORAGE

    /** The Android permission to request for [capability], or null when it needs none. */
    fun permissionFor(capability: CapabilityId): String? = permissions[capability]

    /** Every runtime permission to request for [capability] on [sdk]; empty when none, or when it is granted in settings instead. */
    fun permissionsFor(capability: CapabilityId, sdk: Int = Build.VERSION.SDK_INT): List<String> = when {
        capability == STORAGE -> if (sdk >= ALL_FILES_API) emptyList() else listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> listOfNotNull(permissionFor(capability))
    }

    /** True when [capability] is granted on a settings page ("all files access") rather than by a prompt. */
    fun needsSettingsPage(capability: CapabilityId, sdk: Int = Build.VERSION.SDK_INT): Boolean = capability == STORAGE && sdk >= ALL_FILES_API

    /** Whether Android granted [capability]; true when it needs nothing. Not for [HEALTH_CAPABILITIES]. */
    fun isGranted(context: Context, capability: CapabilityId): Boolean = when {
        needsSettingsPage(capability) -> Environment.isExternalStorageManager()
        else -> permissionsFor(capability).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
