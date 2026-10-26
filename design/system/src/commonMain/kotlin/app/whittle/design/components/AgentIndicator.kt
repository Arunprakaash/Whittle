package app.whittle.design.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.whittle.design.sprites.SpriteImage
import app.whittle.design.tokens.BeaverPose
import app.whittle.design.tokens.BeaverSprite
import app.whittle.design.tokens.WhittleAgentIndicator

enum class AgentState { Thinking, Acting, Done }

/** The agent's state mark: the beaver looks up while thinking, gnaws a stick while acting and smiles when done. */
@Composable
fun AgentIndicator(state: AgentState, modifier: Modifier = Modifier) {
    val pose = when (state) {
        AgentState.Thinking -> BeaverPose.Thinking
        AgentState.Acting -> BeaverPose.Working
        AgentState.Done -> BeaverPose.Done
    }
    // Decorative: the status text beside it says what the agent is doing.
    SpriteImage(BeaverSprite, pose.key, contentDescription = null, modifier = modifier.size(WhittleAgentIndicator.boxSize))
}
