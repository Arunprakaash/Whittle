package app.whittle.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.whittle.design.tokens.WhittleAgentIndicator
import app.whittle.design.tokens.WhittleMotion
import app.whittle.design.tokens.WhittleSize

enum class AgentState { Thinking, Acting, Done }

/** The agent's state mark: one dot that breathes while thinking, an orbiting arc while acting, a check when done. */
@Composable
fun AgentIndicator(state: AgentState, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.size(WhittleAgentIndicator.boxSize), contentAlignment = Alignment.Center) {
        when (state) {
            AgentState.Thinking -> {
                val breath by rememberInfiniteTransition(label = "agentBreathe").animateFloat(
                    initialValue = 1f,
                    targetValue = BREATHE_MIN_SCALE,
                    animationSpec = infiniteRepeatable(tween(WhittleMotion.AGENT_BREATHE_MILLIS / 2), RepeatMode.Reverse),
                    label = "agentBreatheScale",
                )
                Dot(Modifier.graphicsLayer { scaleX = breath; scaleY = breath; alpha = breath })
            }
            AgentState.Acting -> {
                val angle by rememberInfiniteTransition(label = "agentOrbit").animateFloat(
                    initialValue = 0f,
                    targetValue = FULL_TURN,
                    animationSpec = infiniteRepeatable(tween(WhittleMotion.AGENT_ORBIT_MILLIS, easing = LinearEasing)),
                    label = "agentOrbitAngle",
                )
                Dot()
                Canvas(Modifier.size(WhittleAgentIndicator.boxSize - ORBIT_INSET * 2)) {
                    rotate(angle) {
                        drawArc(color, startAngle = 0f, sweepAngle = ORBIT_SWEEP, useCenter = false, style = Stroke(ORBIT_STROKE.toPx()))
                    }
                }
            }
            AgentState.Done -> WhittleIcon("check", size = WhittleSize.Icon.m, tint = color)
        }
    }
}

@Composable
private fun Dot(modifier: Modifier = Modifier) {
    Box(modifier.size(WhittleAgentIndicator.dotSize).background(MaterialTheme.colorScheme.primary, CircleShape))
}

private const val BREATHE_MIN_SCALE = 0.6f
private const val FULL_TURN = 360f
private const val ORBIT_SWEEP = 90f
private val ORBIT_INSET = 5.dp
private val ORBIT_STROKE = 2.dp
