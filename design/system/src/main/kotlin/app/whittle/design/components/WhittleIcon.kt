package app.whittle.design.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.whittle.design.icons.MaterialSymbols
import app.whittle.design.tokens.WhittleSize

/** A bundled Material Symbol by name. Unknown names draw nothing; config tests keep names valid. */
@Composable
fun WhittleIcon(
    name: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fill: Boolean = false,
    size: Dp = WhittleSize.Icon.xl,
    tint: Color = LocalContentColor.current,
) {
    val drawable = MaterialSymbols.drawable(name, fill) ?: return
    Icon(painterResource(drawable), contentDescription, modifier.size(size), tint)
}
