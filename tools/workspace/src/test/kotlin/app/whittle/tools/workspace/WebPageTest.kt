package app.whittle.tools.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebPageTest {
    private val extensions = listOf(".tsx", ".ts", ".jsx", ".js")

    @Test
    fun `finds the local scripts to bundle, wherever the page sits`() {
        val page = WebPage(
            """<script type="module" src="/src/main.tsx"></script><script src="https://cdn.example/x.js"></script>""" +
                """<script src="./util.ts"></script><script>inline()</script><script src="../lib/a.js"></script>""",
            location = "app/index.html",
        )
        assertEquals(listOf("src/main.tsx", "app/util.ts", "lib/a.js"), page.scripts(extensions))
    }

    @Test
    fun `the built page loads the bundles as modules, links their CSS and keeps other links working`() {
        val html = """<html><head><link rel="icon" href="favicon.svg"><link rel="stylesheet" href="https://cdn.example/a.css"></head>""" +
            """<body><a href="#top">Top</a><img src="img/logo.png"><script src="src/main.tsx" defer></script></body></html>"""
        val built = WebPage(html, "index.html").built(mapOf("src/main.tsx" to WebPage.Bundle("main.js", "main.css")), modules = true)

        assertTrue("""<script type="module" src="./main.js" defer></script>""" in built, built)
        assertTrue("""<link rel="stylesheet" href="./main.css"></head>""" in built, built)
        assertTrue("""href="/favicon.svg"""" in built && """src="/img/logo.png"""" in built, built)
        assertTrue("""href="https://cdn.example/a.css"""" in built && """href="#top"""" in built, built)
    }

    @Test
    fun `classic scripts stay classic when the bundles aren't modules`() {
        val built = WebPage("""<script src="main.js"></script>""", "index.html").built(mapOf("main.js" to WebPage.Bundle("main.js", null)), modules = false)
        assertEquals("""<script src="./main.js"></script>""", built)
    }
}
