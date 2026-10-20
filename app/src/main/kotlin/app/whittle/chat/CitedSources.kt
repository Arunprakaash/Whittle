package app.whittle.chat

import app.whittle.design.components.markdownLinks
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A web page an answer cites. */
@Serializable
data class SourceModel(val url: String, val title: String, val host: String)

/**
 * The sources of an answer are the pages it actually cites: its links, in order, then pages the provider
 * reported as cited that the text doesn't link (some providers cite through hidden markers), once each.
 * Pages a search only looked at are left out. Titles come from the provider's citations when it sent
 * them, else from the link text, else the site name.
 *
 * [cited] maps each cleaned address the provider cited to its title (null when it gave none), in order.
 */
object CitedSources {
    fun from(texts: List<String>, cited: Map<String, String?>): List<SourceModel> {
        val linked = texts.flatMap(::markdownLinks).mapNotNull { link ->
            val url = clean(link.url) ?: return@mapNotNull null
            val host = url.host.removePrefix(WWW_PREFIX)
            val label = link.label.trim().takeUnless { it.equals(host, ignoreCase = true) || it.equals(url.host, ignoreCase = true) }
            SourceModel(url.toString(), cited[url.toString()] ?: label ?: host, host)
        }
        val reported = cited.mapNotNull { (address, title) ->
            val url = clean(address) ?: return@mapNotNull null
            val host = url.host.removePrefix(WWW_PREFIX)
            SourceModel(url.toString(), title ?: host, host)
        }
        return (linked + reported).distinctBy { it.url }
    }

    /** The address without tracking parameters (`utm_…`) or a fragment; used to open and to match citations. */
    fun clean(url: String): HttpUrl? {
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps || it.scheme == HTTP } ?: return null
        val builder = parsed.newBuilder().fragment(null)
        parsed.queryParameterNames.filter { it.startsWith(TRACKING_PREFIX) }.forEach(builder::removeAllQueryParameters)
        return builder.build()
    }

    private const val WWW_PREFIX = "www."
    private const val TRACKING_PREFIX = "utm_"
    private const val HTTP = "http"
}
