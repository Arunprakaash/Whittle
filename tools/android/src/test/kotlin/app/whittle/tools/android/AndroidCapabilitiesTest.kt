package app.whittle.tools.android

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCapabilitiesTest {
    @Test
    fun storageNeedsNoRuntimePermissionOn30Plus() {
        assertEquals(emptyList(), AndroidCapabilities.permissionsFor(AndroidCapabilities.STORAGE, sdk = 30))
        assertEquals(emptyList(), AndroidCapabilities.permissionsFor(AndroidCapabilities.STORAGE, sdk = 36))
        assertTrue(AndroidCapabilities.needsSettingsPage(AndroidCapabilities.STORAGE, sdk = 30))
    }

    @Test
    fun storageAsksForBothLegacyPermissionsBelow30() {
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            AndroidCapabilities.permissionsFor(AndroidCapabilities.STORAGE, sdk = 29),
        )
        assertFalse(AndroidCapabilities.needsSettingsPage(AndroidCapabilities.STORAGE, sdk = 29))
    }

    @Test
    fun otherCapabilitiesKeepTheirOnePermission() {
        assertEquals(listOf(Manifest.permission.READ_CONTACTS), AndroidCapabilities.permissionsFor(AndroidCapabilities.CONTACTS, sdk = 36))
        assertFalse(AndroidCapabilities.needsSettingsPage(AndroidCapabilities.CONTACTS, sdk = 36))
    }
}
