package app.whittle.apps

import kotlin.test.Test
import kotlin.test.assertEquals

class SelfUpdateCheckTest {
    private val own = "app.whittle"

    private fun facts(packageName: String = own, versionCode: Long = 30, sharedUserId: Boolean = false) =
        ApkFacts(packageName, versionCode, emptyList(), emptyList(), sharedUserId, minSdk = 26, targetSdk = 28)

    @Test
    fun `a newer or same build of Whittle, signed with its key, may install`() {
        assertEquals(InstallCheck.OK, checkSelfUpdate(facts(), signerMatches = true, ownPackage = own, installedVersion = 29))
        assertEquals(InstallCheck.OK, checkSelfUpdate(facts(versionCode = 29), signerMatches = true, ownPackage = own, installedVersion = 29))
    }

    @Test
    fun `another key, another app, an older build or a shared user id is refused`() {
        assertEquals(InstallCheck.CHANGED, checkSelfUpdate(facts(), signerMatches = false, ownPackage = own, installedVersion = 29))
        assertEquals(InstallCheck.CHANGED, checkSelfUpdate(facts(packageName = "app.other"), signerMatches = true, ownPackage = own, installedVersion = 29))
        assertEquals(InstallCheck.CHANGED, checkSelfUpdate(facts(versionCode = 28), signerMatches = true, ownPackage = own, installedVersion = 29))
        assertEquals(InstallCheck.CHANGED, checkSelfUpdate(facts(sharedUserId = true), signerMatches = true, ownPackage = own, installedVersion = 29))
    }
}
