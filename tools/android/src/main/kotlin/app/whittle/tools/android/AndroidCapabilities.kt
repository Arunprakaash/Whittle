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

    /**
     * Files in shared storage (Downloads, photos, documents). Whittle targets SDK 28, so Android keeps it on legacy
     * storage, where the ordinary READ and WRITE prompt gives file access to /sdcard on every version; only when it
     * isn't on legacy storage is access the all files access page in settings ([StorageGrant]).
     */
    val STORAGE = CapabilityId("storage")

    private val permissions = mapOf(
        CONTACTS to Manifest.permission.READ_CONTACTS,
        CALENDAR to Manifest.permission.READ_CALENDAR,
        CALENDAR_WRITE to Manifest.permission.WRITE_CALENDAR,
    )

    /** Whether Android or Health Connect knows how to grant [capability], so config may name it. */
    fun isKnown(capability: CapabilityId): Boolean = capability in permissions || capability in HEALTH_CAPABILITIES || capability == STORAGE

    /** The Android permission to request for [capability], or null when it needs none. */
    fun permissionFor(capability: CapabilityId): String? = permissions[capability]

    /** Every runtime permission to request for [capability]; empty when none, or when it is granted on a settings page instead. */
    fun permissionsFor(capability: CapabilityId, grant: StorageGrant = storageGrant()): List<String> = when {
        capability != STORAGE -> listOfNotNull(permissionFor(capability))
        grant == StorageGrant.PROMPT -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> emptyList()
    }

    /** True when [capability] is granted on a settings page ("all files access") rather than by a prompt. */
    fun needsSettingsPage(capability: CapabilityId, grant: StorageGrant = storageGrant()): Boolean =
        capability == STORAGE && grant == StorageGrant.ALL_FILES_PAGE

    /** How storage is granted on Android [sdk], for an app [legacy] storage applies to or not. */
    fun storageGrant(sdk: Int = Build.VERSION.SDK_INT, legacy: Boolean = isLegacyStorage()): StorageGrant =
        if (legacy || sdk < Build.VERSION_CODES.R) StorageGrant.PROMPT else StorageGrant.ALL_FILES_PAGE

    /** Whether Android keeps Whittle on legacy storage: always before Android 10, and after it for apps targeting SDK 28. */
    private fun isLegacyStorage(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Environment.isExternalStorageLegacy()

    /**
     * Whether Android granted [capability]; true when it needs nothing. Not for [HEALTH_CAPABILITIES]. Storage by the
     * prompt counts as granted once reading is: deleting needs writing too, which the prompt asks for alongside.
     */
    fun isGranted(context: Context, capability: CapabilityId): Boolean = when {
        capability != STORAGE -> permissionsFor(capability).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        storageGrant() == StorageGrant.PROMPT -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }
}

/** How Android grants storage to Whittle: its ordinary prompt, or the all files access page in settings. */
enum class StorageGrant { PROMPT, ALL_FILES_PAGE }
