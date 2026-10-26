package app.whittle.design.icons

import app.whittle.design.resources.Res
import app.whittle.design.resources.brand_health_connect
import app.whittle.design.resources.brand_tailscale
import org.jetbrains.compose.resources.DrawableResource

/**
 * Every icon config can name: the bundled Material Symbols, plus one-colour brand marks for services a setting works
 * through (Health Connect, Tailscale). Brand marks are tinted by the theme like the symbols, so they never bring their own colours.
 */
object WhittleIcons {
    private val brands: Map<String, DrawableResource> = mapOf(
        "health_connect" to Res.drawable.brand_health_connect,
        "tailscale" to Res.drawable.brand_tailscale,
    )

    val names: Set<String> get() = MaterialSymbols.names + brands.keys

    /** The symbol (filled when one is bundled and [fill] is set) or brand mark called [name]; null when there's none. */
    fun drawable(name: String, fill: Boolean = false): DrawableResource? = MaterialSymbols.drawable(name, fill) ?: brands[name]
}
