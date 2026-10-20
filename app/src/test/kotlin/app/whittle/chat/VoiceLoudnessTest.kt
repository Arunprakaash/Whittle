package app.whittle.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceLoudnessTest {
    @Test
    fun `steady noise reads as quiet however loud the phone reports it`() {
        val loudness = VoiceLoudness()
        val levels = List(30) { loudness.level(10f) }
        assertTrue(levels.all { it < 0.05f }, "steady noise rose to ${levels.max()}")
    }

    @Test
    fun `speech above the room's noise rises, louder rises more`() {
        val loudness = VoiceLoudness(rangeDb = 9f)
        repeat(10) { loudness.level(-2f) }
        val soft = loudness.level(2f)
        val loud = loudness.level(7f)
        assertTrue(soft in 0.3f..0.6f, "soft speech was $soft")
        assertTrue(loud > soft, "loud $loud not above soft $soft")
        assertEquals(1f, loudness.level(20f))
    }

    @Test
    fun `the floor follows the room when it gets quieter`() {
        val loudness = VoiceLoudness()
        repeat(10) { loudness.level(6f) }
        assertEquals(0f, loudness.level(-2f))
        assertTrue(loudness.level(4f) > 0.5f)
    }
}
