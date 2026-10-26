package app.whittle.design.icons

import androidx.annotation.DrawableRes
import app.whittle.design.R

/**
 * Every icon config can name: the bundled Material Symbols, plus one-colour brand marks for services a setting works
 * through (Health Connect, Tailscale). Brand marks are tinted by the theme like the symbols, so they never bring their own colours.
 */
object WhittleIcons {
    private val brands: Map<String, Int> = mapOf(
        "health_connect" to R.drawable.brand_health_connect,
        "tailscale" to R.drawable.brand_tailscale,
    )

    val names: Set<String> get() = MaterialSymbols.names + brands.keys

    /** The symbol (filled when one is bundled and [fill] is set) or brand mark called [name]; null when there's none. */
    @DrawableRes
    fun drawable(name: String, fill: Boolean = false): Int? = MaterialSymbols.drawable(name, fill) ?: brands[name]
}
