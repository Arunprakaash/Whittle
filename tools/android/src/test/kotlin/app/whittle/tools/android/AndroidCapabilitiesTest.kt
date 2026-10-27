package app.whittle.tools.android

import android.Manifest
import app.whittle.tools.api.CapabilityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCapabilitiesTest {
    @Test
    fun storageIsTheOrdinaryPromptWhileLegacyStorageHolds() {
        // Targeting SDK 28 keeps Whittle on legacy storage on Android 11 to 16 as well.
        listOf(28, 29, 30, 33, 36).forEach { sdk ->
            val grant = AndroidCapabilities.storageGrant(sdk, legacy = true)
            assertEquals(StorageGrant.PROMPT, grant)
            assertEquals(
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                AndroidCapabilities.permissionsFor(AndroidCapabilities.STORAGE, grant),
            )
            assertFalse(AndroidCapabilities.needsSettingsPage(AndroidCapabilities.STORAGE, grant))
        }
    }

    @Test
    fun storageIsTheAllFilesPageOnlyWithoutLegacyStorageFromAndroid11() {
        val grant = AndroidCapabilities.storageGrant(sdk = 30, legacy = false)
        assertEquals(StorageGrant.ALL_FILES_PAGE, grant)
        assertEquals(emptyList(), AndroidCapabilities.permissionsFor(AndroidCapabilities.STORAGE, grant))
        assertTrue(AndroidCapabilities.needsSettingsPage(AndroidCapabilities.STORAGE, grant))
        // Android 10 without legacy storage has no all files page; the prompt is all there is.
        assertEquals(StorageGrant.PROMPT, AndroidCapabilities.storageGrant(sdk = 29, legacy = false))
    }

    @Test
    fun otherCapabilitiesKeepTheirOnePermission() {
        val grant = StorageGrant.ALL_FILES_PAGE
        assertEquals(listOf(Manifest.permission.READ_CONTACTS), AndroidCapabilities.permissionsFor(AndroidCapabilities.CONTACTS, grant))
        assertFalse(AndroidCapabilities.needsSettingsPage(AndroidCapabilities.CONTACTS, grant))
    }

    @Test
    fun storageIsKnownSoConfigMayNameIt() {
        assertTrue(AndroidCapabilities.isKnown(AndroidCapabilities.STORAGE))
        assertFalse(AndroidCapabilities.isKnown(CapabilityId("files")))
    }
}
