package app.whittle.export

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Packs the real shell APK (the build passes its path) the way an export does, and checks what Android will read. */
class AppPackagerTest {
    private val shell = File(System.getProperty(SHELL_PROPERTY))
    private val outputDirectory = File(System.getProperty(OUTPUT_PROPERTY)).apply { mkdirs() }

    @Test
    fun `an exported app has its own identity, icon and web files`() {
        val site = File(outputDirectory, "site").apply { mkdirs() }
        val page = File(site, "habits.html").apply { writeText("<div id=root></div><script src=app.js></script>") }
        val script = File(site, "app.js").apply { writeText("console.log('hi')") }
        val icon = byteArrayOf(1, 2, 3, 4)
        val bundle = AppBundle(PACKAGE, LABEL, icon, mapOf("index.html" to page, "habits.html" to page, "app.js" to script))
        val apk = File(outputDirectory, "app-unsigned.apk")

        apk.outputStream().use { out -> shell.inputStream().use { AppPackager(PACKAGE_PLACEHOLDER, LABEL_PLACEHOLDER).pack(it, bundle, out) } }

        ZipFile(apk).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertFalse(names.any { it.startsWith("META-INF/") }, "the shell's signature files are dropped")
            assertTrue(listOf("assets/www/index.html", "assets/www/habits.html", "assets/www/app.js").all { it in names })
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            val text = String(manifest, Charsets.UTF_16LE) + String(manifest, Charsets.UTF_8)
            assertTrue(PACKAGE in text && LABEL in text)
            assertFalse(PACKAGE_PLACEHOLDER in text || LABEL_PLACEHOLDER in text)
            val iconEntry = names.single { it.startsWith("res/") && it.endsWith(".png") }
            assertContentEquals(icon, zip.getInputStream(zip.getEntry(iconEntry)).readBytes())
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc").method)
        }
    }

    private companion object {
        const val SHELL_PROPERTY = "whittle.exportShell.apk"
        const val OUTPUT_PROPERTY = "whittle.exportShell.testOutput"
        const val PACKAGE_PLACEHOLDER = "app.whittle.exported.placeholder"
        const val LABEL_PLACEHOLDER = "WhittleExportedAppLabelPlaceholder"
        const val PACKAGE = "app.whittle.apps.habits.a1b2c3d4"
        const val LABEL = "Little by Little"
    }
}
