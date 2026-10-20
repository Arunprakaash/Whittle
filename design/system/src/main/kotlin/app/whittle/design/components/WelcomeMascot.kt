package app.whittle.design.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.whittle.design.sprites.SpriteImage
import app.whittle.design.tokens.BeaverPose
import app.whittle.design.tokens.BeaverSprite
import app.whittle.design.tokens.WhittleSize

/** The beaver idling on the start screen: it breathes and blinks. Decorative; the headline beside it speaks. */
@Composable
fun WelcomeMascot(modifier: Modifier = Modifier) {
    SpriteImage(BeaverSprite, BeaverPose.Idle.key, contentDescription = null, modifier = modifier.size(WhittleSize.welcomeMascot))
}
