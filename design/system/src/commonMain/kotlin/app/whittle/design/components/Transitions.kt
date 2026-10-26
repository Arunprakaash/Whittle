package app.whittle.design.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.Alignment
import app.whittle.design.tokens.SpringToken
import app.whittle.design.tokens.WhittleMotion

/**
 * Surfaces that live at the bottom of the screen arrive from below: a spatial spring carries them up from their
 * own height while a quicker effects spring fades them in, and they leave the way they came.
 */
object WhittleTransitions {
    fun riseIn(motion: SpringToken = WhittleMotion.spatialDefault): EnterTransition =
        slideInVertically(motion.spec()) { height -> height } + fadeIn(WhittleMotion.effectsDefault.spec())

    fun sinkOut(motion: SpringToken = WhittleMotion.spatialDefault): ExitTransition =
        slideOutVertically(motion.spec()) { height -> height } + fadeOut(WhittleMotion.effectsFast.spec())

    /**
     * For a surface that sits in a column above another (the status line above the composer): its room opens
     * as it rises, so what's below doesn't jump, and it slides out from behind what's below it.
     */
    fun riseInPlace(motion: SpringToken = WhittleMotion.spatialDefault): EnterTransition =
        riseIn(motion) + expandVertically(motion.spec(), expandFrom = Alignment.Top, clip = false)

    fun sinkOutPlace(motion: SpringToken = WhittleMotion.spatialDefault): ExitTransition =
        sinkOut(motion) + shrinkVertically(motion.spec(), shrinkTowards = Alignment.Top, clip = false)
}
