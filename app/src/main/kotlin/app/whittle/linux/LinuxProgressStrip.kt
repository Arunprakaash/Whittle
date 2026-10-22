package app.whittle.linux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import app.whittle.design.sprites.SpriteImage
import app.whittle.design.tokens.BeaverPose
import app.whittle.design.tokens.BeaverSprite
import app.whittle.design.tokens.WhittleTextStyles
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.design.components.WhittleIcon
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/**
 * Linux's setup while the user does something else: at the top of the connect screen, and [compact] above the
 * chat's composer until it's done. Nothing shows before setup starts or once Linux is ready; a failed setup stays,
 * with Try again.
 */
@Composable
fun LinuxProgressStrip(graph: AppGraph, compact: Boolean, modifier: Modifier = Modifier) {
    val state by graph.linux.state.collectAsStateWithLifecycle()
    val showing = state.takeIf { it is LinuxState.SettingUp || it is LinuxState.Failed }
    // Keeps the last state to show while the strip slides away once Linux is ready.
    var last by remember { mutableStateOf(showing) }
    if (showing != null) last = showing
    AnimatedVisibility(showing != null, modifier) {
        last?.let { shown -> Strip(shown, graph.linux.image?.name.orEmpty(), compact) }
    }
}

@Composable
private fun Strip(state: LinuxState, imageName: String, compact: Boolean) {
    if (compact) CompactStrip(state, imageName) else FullStrip(state, imageName)
}

/** On the connect screen: the beaver at work, what setup is doing, and a bar. */
@Composable
private fun FullStrip(state: LinuxState, imageName: String) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.lIncreased))
            .padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Mark(state, WhittleSize.Tile.s)
            Column(Modifier.weight(1f)) {
                when (state) {
                    is LinuxState.SettingUp -> {
                        Text(stringResource(R.string.linux_strip_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            LinuxSteps.label(context, state.step, done = false, imageName),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    is LinuxState.Failed -> {
                        Text(stringResource(R.string.linux_strip_failed), style = MaterialTheme.typography.titleSmall)
                        Text(state.reason, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    else -> Unit
                }
            }
            when (state) {
                is LinuxState.SettingUp -> if (state.totalBytes > 0) {
                    Text(stringResource(R.string.linux_progress_percent, LinuxSteps.percent(state)), style = WhittleTextStyles.mono, color = colors.onSurfaceVariant)
                }
                is LinuxState.Failed -> TryAgain()
                else -> Unit
            }
        }
        if (state is LinuxState.SettingUp) Bar(state, Modifier.fillMaxWidth())
    }
}

/** Above the chat's composer: one line and a short bar, until Linux is ready. */
@Composable
private fun CompactStrip(state: LinuxState, imageName: String) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.l))
            .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        Mark(state, WhittleSize.Icon.xl)
        Text(
            when (state) {
                is LinuxState.SettingUp -> stringResource(R.string.linux_strip_finishing, LinuxSteps.label(context, state.step, done = false, imageName))
                else -> stringResource(R.string.linux_strip_failed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when (state) {
            is LinuxState.SettingUp -> Bar(state, Modifier.width(WhittleSize.Tile.xl))
            is LinuxState.Failed -> TryAgain()
            else -> Unit
        }
    }
}

/** The beaver at work while it sets up; an error mark once it stopped. */
@Composable
private fun Mark(state: LinuxState, size: Dp) {
    if (state is LinuxState.Failed) {
        WhittleIcon("error", size = size, tint = MaterialTheme.colorScheme.error)
    } else {
        SpriteImage(BeaverSprite, BeaverPose.Working.key, contentDescription = null, modifier = Modifier.size(size))
    }
}

/** A plain bar: filled by the count where setup has one, moving where it doesn't. */
@Composable
private fun Bar(state: LinuxState.SettingUp, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    if (state.totalBytes > 0) {
        LinearProgressIndicator(
            progress = { LinuxSteps.percent(state) / PERCENT },
            modifier = modifier,
            color = colors.primary,
            trackColor = colors.surfaceContainerHigh,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(modifier = modifier, color = colors.primary, trackColor = colors.surfaceContainerHigh, gapSize = 0.dp)
    }
}

@Composable
private fun TryAgain() {
    val context = LocalContext.current
    TextButton(onClick = { LinuxSetupService.setUp(context) }) {
        Text(stringResource(R.string.linux_try_again), style = MaterialTheme.typography.labelLarge)
    }
}

private const val PERCENT = 100f
