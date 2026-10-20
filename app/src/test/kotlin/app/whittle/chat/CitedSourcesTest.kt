package app.whittle.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class CitedSourcesTest {
    @Test
    fun `sources are the cited links, once each, without tracking parameters`() {
        val answer = "Meta builds a cluster. ([aiweekly.co](https://aiweekly.co/meta?utm_source=openai))\n" +
            "Tenstorrent raises. ([aiweekly.co](https://aiweekly.co/tenstorrent?utm_source=openai))\n" +
            "Again: ([aiweekly.co](https://aiweekly.co/meta?utm_source=openai#top)) and [TechCrunch story](https://www.techcrunch.com/x?id=7)"
        val titles = mapOf("https://aiweekly.co/meta" to "Meta's Prometheus cluster")

        val sources = CitedSources.from(listOf("Searching first.", answer), titles)

        assertEquals(
            listOf(
                SourceModel("https://aiweekly.co/meta", "Meta's Prometheus cluster", "aiweekly.co"),
                SourceModel("https://aiweekly.co/tenstorrent", "aiweekly.co", "aiweekly.co"),
                SourceModel("https://www.techcrunch.com/x?id=7", "TechCrunch story", "techcrunch.com"),
            ),
            sources,
        )
    }

    @Test
    fun `an answer without links has no sources`() {
        assertEquals(emptyList(), CitedSources.from(listOf("It's 1:05 PM."), emptyMap()))
    }

    @Test
    fun `pages the provider cited without a link in the text are sources too`() {
        val sources = CitedSources.from(listOf("Chetak has the longest range."), linkedMapOf("https://www.chetak.com/c3001?utm_source=openai" to "Chetak C3001", "https://ather.com/" to null))
        assertEquals(listOf("Chetak C3001", "ather.com"), sources.map { it.title })
    }
}
