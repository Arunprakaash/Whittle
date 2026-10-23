package app.whittle.tools.android

import kotlin.test.Test
import kotlin.test.assertEquals

class AppMatchTest {
    private val apps = listOf("Spotify", "Google Maps", "Maps Go", "Messages", "Phone")

    @Test
    fun `an exact name wins over names that contain it`() {
        assertEquals(listOf("Phone"), AppMatch.find("phone", apps))
        assertEquals(listOf("Google Maps", "Maps Go"), AppMatch.find("maps", apps))
    }

    @Test
    fun `with no match, names sharing a word are offered`() {
        assertEquals(emptyList(), AppMatch.find("google maps app", apps))
        assertEquals(listOf("Google Maps", "Maps Go"), AppMatch.closest("google maps app", apps))
    }
}
