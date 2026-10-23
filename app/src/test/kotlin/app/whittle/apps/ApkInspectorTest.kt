package app.whittle.apps

import android.content.pm.PackageManager
import kotlin.test.Test
import kotlin.test.assertTrue

class ApkInspectorTest {
    @Test
    fun `facts include components declared disabled, which the app could enable itself`() {
        assertTrue(FACT_FLAGS and PackageManager.MATCH_DISABLED_COMPONENTS != 0)
        assertTrue(FACT_FLAGS and PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS != 0)
    }
}
