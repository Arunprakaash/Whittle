package app.whittle.chat

import app.whittle.apps.ApkFacts
import app.whittle.apps.AppManifest
import app.whittle.apps.PowerfulDeclaration
import app.whittle.apps.PowerfulKind
import app.whittle.chat.ui.AppCardState
import app.whittle.chat.ui.appCardState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppCardStateTest {
    private val overlay = PowerfulDeclaration("android.permission.SYSTEM_ALERT_WINDOW", PowerfulKind.PERMISSION, "Draws over apps")
    private val card = ThreadItem.App("k", "Notes", "app.whittle.made.notes", 3, "notes/build/notes-3.apk", emptyList(), listOf(overlay))

    @Test
    fun `buttons follow the installed version`() {
        assertEquals(AppCardState.INSTALL, appCardState(card.versionCode, null))
        assertEquals(AppCardState.UPDATE, appCardState(card.versionCode, 2))
        assertEquals(AppCardState.OPEN, appCardState(card.versionCode, 3))
    }

    @Test
    fun `the card installs its own APK, not a newer one`() {
        // A newer build is installed: this older card never downgrades, it just opens.
        assertEquals(AppCardState.NEWER_INSTALLED, appCardState(card.versionCode, 5))
        assertEquals(File("/ws/notes/build/notes-3.apk"), resolveApk(File("/ws"), card.apkPath))
    }

    @Test
    fun `an APK path that leaves the workspace is not resolved`() {
        assertNull(resolveApk(File("/ws"), "../etc/evil.apk"))
        assertNull(resolveApk(File("/ws"), "/etc/evil.apk"))
    }

    @Test
    fun `an update warns only about declarations the installed app lacks`() {
        assertEquals(listOf(overlay), addedPowerful(card, null))
        assertEquals(emptyList(), addedPowerful(card, listOf(overlay)))
        assertEquals(emptyList(), addedPowerful(card, listOf(overlay.copy(sentence = "other words"))))
        assertEquals(emptyList(), addedPowerful(card, listOf(PowerfulDeclaration("x", PowerfulKind.PERMISSION, "x"), overlay)))
    }

    @Test
    fun `a useless uses-permission with a binding name does not hide the real service later`() {
        val listener = PowerfulDeclaration("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", PowerfulKind.SERVICE_PERMISSION, "Reads notifications")
        val configured = listOf(listener, overlay)
        // v1 only requests the binding permission, which classify does not flag.
        val installed = AppManifest.classify(ApkFacts("p", 1L, listOf(listener.name), emptyList(), false, 26, 35), configured).powerful
        assertEquals(emptyList(), installed)
        // v2 adds the real service.
        assertEquals(listOf(listener), addedPowerful(card.copy(powerful = listOf(listener)), installed))
    }

    @Test
    fun `a component permission with an overlay name does not hide the real request later`() {
        val installed = AppManifest.classify(ApkFacts("p", 1L, emptyList(), listOf(overlay.name), false, 26, 35), listOf(overlay)).powerful
        assertEquals(emptyList(), installed)
        assertEquals(listOf(overlay), addedPowerful(card, installed))
    }

    @Test
    fun `a build result becomes a card`() {
        val json = """{"app":"Notes","package":"app.whittle.made.notes","versionCode":3,"apk":"notes/build/notes-3.apk","sizeBytes":10,"permissions":["android.permission.CAMERA"],"powerful":[{"name":"${overlay.name}","kind":"PERMISSION","sentence":"Draws over apps"}]}"""
        val made = appCardFromBuild("k", json)
        assertEquals(ThreadItem.App("k", "Notes", "app.whittle.made.notes", 3, "notes/build/notes-3.apk", listOf("android.permission.CAMERA"), listOf(overlay)), made)
        assertNull(appCardFromBuild("k", "not json"))
    }

    @Test
    fun `a rebuilt app's card moves to the end of the thread and keeps its key`() {
        val user = ThreadItem.User("u", "make it blue", steer = false)
        val reply = ThreadItem.Narration("n", "Done", streaming = false)
        val rebuilt = card.copy(key = "new", versionCode = 4, apkPath = "notes/build/notes-4.apk")
        assertEquals(listOf(user, reply, rebuilt.copy(key = "k")), placeAppCard(listOf(user, card, reply), rebuilt))
        // A first build just adds its card.
        assertEquals(listOf(user, rebuilt), placeAppCard(listOf(user), rebuilt))
    }

    @Test
    fun `a capability card is answered only while it is still asking`() {
        val asking = ThreadItem.Capability(
            "c1", app.whittle.core.model.RunId("run"), app.whittle.tools.api.CapabilityId("app_building"),
            app.whittle.tools.presentation.CapabilityPresentation("build", "Set up?", "", "Allow", "Not now", "Allowed", "Not allowed"),
        )
        assertEquals(true, stillAsking(listOf(card, asking), "c1"))
        // Declined while setup ran: the card is a chip now, and setup's end must leave it alone.
        assertEquals(false, stillAsking(listOf(card, ThreadItem.Resolved("c1", "Not allowed", declined = true)), "c1"))
        assertEquals(false, stillAsking(emptyList(), "c1"))
    }

    @Test
    fun `the project folder comes from the card's APK path`() {
        assertEquals("notes", projectOf(card))
    }

    @Test
    fun `an install that lost its session stops installing`() {
        // The receiver never fired: the session is gone and the build isn't installed.
        assertEquals(false, stillInstalling(installing = true, installed = 2, cardVersion = 3, sessionId = 7, liveSessions = emptySet()))
        assertEquals(true, stillInstalling(installing = true, installed = 2, cardVersion = 3, sessionId = 7, liveSessions = setOf(7)))
        // Still verifying: no session yet.
        assertEquals(true, stillInstalling(installing = true, installed = null, cardVersion = 3, sessionId = null, liveSessions = emptySet()))
        // The build got installed.
        assertEquals(false, stillInstalling(installing = true, installed = 3, cardVersion = 3, sessionId = 7, liveSessions = setOf(7)))
        assertEquals(false, stillInstalling(installing = false, installed = null, cardVersion = 3, sessionId = 7, liveSessions = setOf(7)))
    }
}
