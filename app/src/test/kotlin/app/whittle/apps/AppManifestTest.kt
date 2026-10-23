package app.whittle.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppManifestTest {
    private val powerful = listOf(
        PowerfulDeclaration("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", PowerfulKind.SERVICE_PERMISSION, "Reads notifications"),
        PowerfulDeclaration("android.permission.SYSTEM_ALERT_WINDOW", PowerfulKind.PERMISSION, "Draws over apps"),
    )

    private fun facts(requested: List<String> = emptyList(), components: List<String> = emptyList(), shared: Boolean = false) =
        ApkFacts("app.whittle.made.x", 1L, requested, components, shared, 26, 35)

    @Test
    fun `a service permission gives one powerful declaration and camera is listed`() {
        val report = AppManifest.classify(
            facts(listOf("android.permission.CAMERA"), listOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")),
            powerful,
        )
        assertEquals(listOf(powerful[0]), report.powerful)
        assertEquals(listOf("android.permission.CAMERA"), report.permissions)
        assertEquals("app.whittle.made.x", report.packageName)
        assertFalse(report.sharedUserId)
    }

    @Test
    fun `a powerful requested permission is caught`() {
        assertEquals(listOf(powerful[1]), AppManifest.classify(facts(listOf("android.permission.SYSTEM_ALERT_WINDOW")), powerful).powerful)
    }

    @Test
    fun `kinds do not cross over`() {
        val report = AppManifest.classify(
            facts(listOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"), listOf("android.permission.SYSTEM_ALERT_WINDOW")),
            powerful,
        )
        assertTrue(report.powerful.isEmpty())
    }

    @Test
    fun `reports sharedUserId`() {
        assertTrue(AppManifest.classify(facts(shared = true), powerful).sharedUserId)
    }
}
