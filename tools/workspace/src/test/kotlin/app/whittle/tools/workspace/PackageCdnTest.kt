package app.whittle.tools.workspace

import app.whittle.core.common.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackageCdnTest {
    private val cdn = PackageCdn(
        PackageCdnConfig(
            baseUrl = "https://cdn.example/",
            query = "target=es2022",
            manifestFile = "package.json",
            manifestFields = listOf("dependencies", "devDependencies"),
            allowedHosts = listOf("cdn.example", "mirror.example"),
            maxModuleBytes = 1_000,
        ),
    )

    @Test
    fun `bare imports map to the CDN with the manifest's version and the subpath`() {
        val versions = mapOf("react-dom" to "^19.1.0", "@tanstack/react-query" to "5.0.0")
        assertEquals("https://cdn.example/react?target=es2022", url("react", versions))
        assertEquals("https://cdn.example/react-dom@%5E19.1.0/client?target=es2022", url("react-dom/client", versions))
        assertEquals("https://cdn.example/@tanstack/react-query@5.0.0?target=es2022", url("@tanstack/react-query", versions))
    }

    @Test
    fun `ranges are percent-encoded and odd specifiers are refused`() {
        assertEquals("https://cdn.example/zod@%3E%3D3%203?target=es2022", url("zod", mapOf("zod" to ">=3 3")))
        listOf("node:fs", "Bad Name", "", "@scope", "https:x").forEach { assertIs<Outcome.Failure>(cdn.url(it, emptyMap()), it) }
    }

    @Test
    fun `versions come from the manifest, skipping files, git and workspace references`() {
        val manifest = """{"dependencies":{"react":"^19.0.0","local":"file:../x","repo":"github:a/b"},"devDependencies":{"react":"18","typescript":"~5.6"}}"""
        assertEquals(mapOf("react" to "^19.0.0", "typescript" to "~5.6"), cdn.versions(manifest))
        assertEquals(emptyMap(), cdn.versions("not json"))
        assertEquals(emptyMap(), cdn.versions(null))
    }

    @Test
    fun `only https on allowed hosts may be downloaded`() {
        assertTrue(cdn.allows("https://cdn.example/react@19/es2022/react.mjs"))
        assertTrue(cdn.allows("https://MIRROR.example/x.js"))
        assertTrue(cdn.allows("https://cdn.example/react@^18.3.1?target=es2022"), "browsers leave ^ in paths")
        assertFalse(cdn.allows("https://cdn.example:8443/react"))
        assertFalse(cdn.allows("https://cdn.example.evil.example/react"))
        assertFalse(cdn.allows("http://cdn.example/react"))
        assertFalse(cdn.allows("https://evil.example/react"))
        assertFalse(cdn.allows("https://user@cdn.example/react"))
        assertFalse(cdn.allows("not a url"))
    }

    private fun url(specifier: String, versions: Map<String, String>): String = assertIs<Outcome.Success<String>>(cdn.url(specifier, versions)).value
}
