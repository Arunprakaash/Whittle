package app.whittle.artifacts

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ArtifactBundlerTest {
    private val root: File = Files.createTempDirectory("workspace").toFile()

    @Test
    fun `local styles, scripts and images are inlined while web links stay`() {
        File(root, "css").mkdirs()
        File(root, "css/app.css").writeText("body { color: red; }")
        File(root, "app.js").writeText("console.log('</script>')")
        File(root, "logo.png").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "index.html").writeText(
            """
            <link rel="stylesheet" href="css/app.css?v=2">
            <link rel="stylesheet" href="https://cdn.example.com/x.css">
            <script type="module" src="app.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
            <img alt="logo" src="logo.png">
            """.trimIndent(),
        )

        val html = ArtifactBundler(maxInlineBytes = 1_000).bundle(root, File(root, "index.html"))

        assertContains(html, "<style>\nbody { color: red; }\n</style>")
        assertContains(html, """<link rel="stylesheet" href="https://cdn.example.com/x.css">""")
        assertContains(html, """<script type="module">""" + "\nconsole.log('<\\/script>')\n</script>")
        assertContains(html, """<script src="https://cdn.jsdelivr.net/npm/chart.js"></script>""")
        assertContains(html, """<img alt="logo" src="data:image/png;base64,AQID">""")
    }

    @Test
    fun `files outside the workspace or too large are never inlined`() {
        val outside = Files.createTempDirectory("elsewhere").toFile()
        File(outside, "secret.css").writeText("secret")
        File(root, "big.css").writeText("x".repeat(50))
        File(root, "index.html").writeText(
            """<link rel="stylesheet" href="../${outside.name}/secret.css"><link rel="stylesheet" href="big.css">""",
        )

        val html = ArtifactBundler(maxInlineBytes = 10).bundle(root, File(root, "index.html"))

        assertFalse("<style>" in html)
        assertEquals(2, Regex("<link").findAll(html).count())
    }
}
