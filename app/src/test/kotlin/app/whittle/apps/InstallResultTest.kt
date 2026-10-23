package app.whittle.apps

import android.content.pm.PackageInstaller
import app.whittle.chat.ThreadItem
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallResultTest {
    private val camera = "android.permission.CAMERA"
    private val overlay = PowerfulDeclaration("android.permission.SYSTEM_ALERT_WINDOW", PowerfulKind.PERMISSION, "Draws over apps")
    private val configured = listOf(overlay)
    private val card = ThreadItem.App(
        key = "k", name = "Notes", packageName = "app.whittle.made.notes", versionCode = 3, apkPath = "notes/build/notes-3.apk",
        permissions = listOf(camera, overlay.name), powerful = listOf(overlay), fromTaintedRun = false,
    )
    private val facts = ApkFacts(card.packageName, 3L, listOf(camera, overlay.name), emptyList(), false)

    @Test
    fun `install results map to what the card says`() {
        assertEquals(InstallResult.KEY_CHANGED, installResult(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures"))
        assertEquals(InstallResult.CANCELLED, installResult(PackageInstaller.STATUS_FAILURE_ABORTED, "User rejected"))
        assertEquals(InstallResult.BLOCKED, installResult(PackageInstaller.STATUS_FAILURE_BLOCKED, null))
        assertEquals(InstallResult.INSTALLED, installResult(PackageInstaller.STATUS_SUCCESS, null))
        assertEquals(InstallResult.FAILED, installResult(PackageInstaller.STATUS_FAILURE_CONFLICT, "something else"))
        assertEquals(InstallResult.FAILED, installResult(PackageInstaller.STATUS_FAILURE_STORAGE, null))
    }

    @Test
    fun `the unchanged build is installable`() {
        assertEquals(InstallCheck.OK, checkInstallable(card, facts, signerMatches = true, configured))
    }

    @Test
    fun `a signer mismatch is refused`() {
        assertEquals(InstallCheck.CHANGED, checkInstallable(card, facts, signerMatches = false, configured))
    }

    @Test
    fun `a different package or version is refused`() {
        assertEquals(InstallCheck.CHANGED, checkInstallable(card, facts.copy(packageName = "com.evil"), true, configured))
        assertEquals(InstallCheck.CHANGED, checkInstallable(card, facts.copy(versionCode = 4L), true, configured))
    }

    @Test
    fun `an added or removed permission is refused`() {
        assertEquals(InstallCheck.CHANGED, checkInstallable(card, facts.copy(requestedPermissions = facts.requestedPermissions + "android.permission.READ_SMS"), true, configured))
        assertEquals(InstallCheck.CHANGED, checkInstallable(card, facts.copy(requestedPermissions = listOf(overlay.name)), true, configured))
    }

    @Test
    fun `a powerful entry that the card did not show is refused`() {
        val listener = PowerfulDeclaration("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", PowerfulKind.SERVICE_PERMISSION, "Reads notifications")
        val withListener = facts.copy(componentPermissions = listOf(listener.name))
        assertEquals(InstallCheck.CHANGED, checkInstallable(card, withListener, true, configured + listener))
        // A powerful entry the card showed but the file no longer declares is refused too.
        assertEquals(InstallCheck.CHANGED, checkInstallable(card, facts.copy(requestedPermissions = listOf(camera)), true, configured))
    }

    @Test
    fun `a shared user id is refused`() {
        assertEquals(InstallCheck.CHANGED, checkInstallable(card, facts.copy(sharedUserId = true), true, configured))
    }
}
