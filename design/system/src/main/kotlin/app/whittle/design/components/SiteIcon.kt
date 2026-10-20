package app.whittle.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleRadius

/** Loads a website's own icon. The app supplies the network and cache; null means "show the fallback". */
fun interface SiteIconSource {
    suspend fun load(host: String): ImageBitmap?
}

/** Where [SiteIcon] gets icons from. The default loads nothing, so previews and tests show the fallback. */
val LocalSiteIcons = staticCompositionLocalOf { SiteIconSource { null } }

/**
 * A website's icon on a light disc of [containerSize], drawn at [iconSize]. Until it loads, or when the
 * site has none, [fallback] is shown in its place.
 */
@Composable
fun SiteIcon(
    host: String,
    containerSize: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val source = LocalSiteIcons.current
    val icon by produceState<ImageBitmap?>(initialValue = null, host, source) { value = source.load(host) }
    val loaded = icon
    if (loaded == null) {
        Box(modifier) { fallback() }
        return
    }
    Box(
        modifier.size(containerSize).clip(CircleShape).background(WhittleTheme.siteIconBackdrop),
        contentAlignment = Alignment.Center,
    ) {
        Image(loaded, contentDescription = null, modifier = Modifier.size(iconSize).clip(RoundedCornerShape(WhittleRadius.xs)))
    }
}
