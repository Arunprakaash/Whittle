package app.whittle.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiteIconLinksTest {
    private val page = "https://www.example.com/news/".toHttpUrl()

    @Test
    fun `picks the closest icon at or above the drawn size, skips svg, ends with favicon ico`() {
        val html = """
            <link rel="icon" href="/icon-16.png" sizes="16x16">
            <LINK REL='icon' TYPE='image/svg+xml' HREF='/icon.svg'>
            <link rel="apple-touch-icon" href="https://cdn.example.com/touch.png">
            <link href="/icon-128.png" rel="shortcut icon" sizes="128x128">
            <link rel="mask-icon" href="/mask.png">
            <link rel="icon" href="http://insecure.example.com/i.png" sizes="96x96">
        """.trimIndent()

        val urls = SiteIconLinks.candidates(html, page, preferredPx = 96).map { it.toString() }

        assertEquals(
            listOf(
                "https://www.example.com/icon-128.png",
                "https://cdn.example.com/touch.png",
                "https://www.example.com/icon-16.png",
                "https://www.example.com/favicon.ico",
            ),
            urls,
        )
    }

    @Test
    fun `only public host names are contacted`() {
        assertEquals("https://techcrunch.com/", publicHomePage("TechCrunch.com")?.toString())
        assertNull(publicHomePage("192.168.1.1"))
        assertNull(publicHomePage("localhost"))
        assertNull(publicHomePage("printer.local"))
        assertNull(publicHomePage("[::1]"))
    }
}
