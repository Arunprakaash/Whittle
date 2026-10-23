package app.whittle.tools.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlTextTest {
    private val page = "https://example.com/phones/".toHttpUrl()

    @Test
    fun `a page reads as its title and text, without scripts, styles or markup`() {
        val html = """
            <!doctype html><html><head><title>Best phones &amp; deals</title><style>p { color: red }</style>
            <script>track("visit")</script></head>
            <body><!-- banner --><h1>Budget phones</h1>
            <p>Our picks for <b>this&nbsp;month</b>.</p><ul><li>Pixel 10a</li><li>Galaxy A57</li></ul>
            <noscript>Turn on JavaScript</noscript></body></html>
        """.trimIndent()

        assertEquals(
            "Best phones & deals\nhttps://example.com/phones/\n\nBudget phones\n\nOur picks for this month.\n\n- Pixel 10a\n- Galaxy A57",
            HtmlText.of(html, page),
        )
    }

    @Test
    fun `links keep their address, as a path on the same site and whole elsewhere`() {
        val text = HtmlText.of(
            """<p>Read the <a class="x" href="review/pixel?page=2">full review</a> or <a href="https://other.org/a?b=1&amp;c=2">this</a>.</p>""",
            page,
        )
        assertTrue("full review (/phones/review/pixel?page=2)" in text, text)
        assertTrue("this (https://other.org/a?b=1&c=2)" in text, text)
    }

    @Test
    fun `links that go nowhere useful keep only their words`() {
        val text = HtmlText.of("""<a href="javascript:void(0)">Menu</a> <a href="mailto:a@b.c">Mail us</a> <a href="/x"><img src="i.png"></a>""", null)
        assertEquals("Menu Mail us", text)
        assertFalse("javascript" in text)
    }

    @Test
    fun `menus, headers, footers and sidebars go, and marked main content is kept alone`() {
        val html = """
            <body><header>Site logo</header><nav><a href="/a">Phones</a></nav>
            <main><h1>Review</h1><p>The phone is good.</p><aside>Related deals</aside></main>
            <footer>Copyright</footer></body>
        """.trimIndent()
        assertEquals("Review\n\nThe phone is good.", HtmlText.of(html, null))
    }

    @Test
    fun `numeric entities decode and unknown ones stay`() {
        assertEquals("café → &unknown;", HtmlText.of("caf&#233; &#x2192; &unknown;", null))
    }
}
