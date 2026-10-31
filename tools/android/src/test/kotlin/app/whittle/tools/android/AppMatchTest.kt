package app.whittle.tools.android

import kotlin.test.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `several matches, or none, go back to the model as names to choose from`() {
        val several = AppMatch.notChosen("maps", apps, "opened")
        assertTrue(!several.isError && "severalMatch" in several.content && "Maps Go" in several.content && "\"opened\":false" in several.content)
        val none = AppMatch.notChosen("google maps app", apps, "opened")
        assertTrue("noneMatchClosest" in none.content && "Google Maps" in none.content)
    }

    @Test
    fun `opening an app's storage page asks first, changes nothing itself, and needs the app's name`() {
        val definition = OpenAppStorageTool.DEFINITION
        assertEquals(app.whittle.tools.api.RiskTier.WRITE_LOCAL, definition.riskTier)
        assertEquals(setOf("app"), definition.inputSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
}
