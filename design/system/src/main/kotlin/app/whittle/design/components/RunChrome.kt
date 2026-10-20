package app.whittle.design.components

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleMotion
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** A finished run's steps fold into this chip ("4 steps · 15s"); tapping it unfolds them. */
@Composable
fun RunSummaryChip(text: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val chevron by animateFloatAsState(if (expanded) QUARTER_TURN else 0f, WhittleMotion.spatialDefault.spec(), label = "summaryChevron")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WhittleRadius.full))
            .background(colors.surfaceContainerLow)
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onToggle() }
            .padding(start = WhittleSpace.s, top = WhittleSpace.xs, end = WhittleSpace.m, bottom = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
    ) {
        WhittleIcon("check_circle", fill = true, size = WhittleSize.Icon.m, tint = colors.primary)
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        WhittleIcon("chevron_right", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(chevron))
    }
}

/**
 * Shown when new content arrives below the fold; tapping jumps to it and resumes following. While the agent is
 * still responding out of view it shrinks to a disc of the same height with three pulsing dots.
 */
@Composable
fun LatestPill(label: String, modifier: Modifier = Modifier, working: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Box(
        modifier = modifier
            .height(WhittleSize.Tile.s)
            .clip(RoundedCornerShape(WhittleRadius.full))
            .background(colors.inverseSurface)
            .clickable(role = Role.Button, onClickLabel = label) { haptics.perform(WhittleHaptic.Tap); onClick() }
            .animateContentSize(WhittleMotion.spatialDefault.spec()),
        contentAlignment = Alignment.Center,
    ) {
        if (working) {
            TypingDots(colors.inversePrimary, Modifier.size(WhittleSize.Tile.s))
        } else {
            Row(
                Modifier.padding(start = WhittleSpace.s, end = WhittleSpace.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
            ) {
                WhittleIcon("arrow_downward", size = WhittleSize.Icon.m, tint = colors.inverseOnSurface)
                Text(label, style = MaterialTheme.typography.labelLarge, color = colors.inverseOnSurface)
            }
        }
    }
}

/** Three dots pulsing one after another: someone is still talking. */
@Composable
private fun TypingDots(color: Color, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "typingDots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TYPING_DOT_GAP, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        repeat(TYPING_DOTS) { index ->
            val alpha by pulse.animateFloat(
                initialValue = TYPING_DIM,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(WhittleMotion.AGENT_BREATHE_MILLIS / 2),
                    RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * WhittleMotion.AGENT_BREATHE_MILLIS / (2 * TYPING_DOTS)),
                ),
                label = "typingDot$index",
            )
            Box(Modifier.size(TYPING_DOT_SIZE).graphicsLayer { this.alpha = alpha }.background(color, CircleShape))
        }
    }
}

private const val TYPING_DOTS = 3
private const val TYPING_DIM = 0.3f
private val TYPING_DOT_SIZE = 5.dp
private val TYPING_DOT_GAP = 3.dp

/** Speaking instead of typing: [listening] while the words come in, [level] (0–1) how loud they are. */
data class ComposerVoice(
    val listening: Boolean,
    val level: Float,
    val listeningPlaceholder: String,
    val startLabel: String,
    val stopLabel: String,
    val onToggle: () -> Unit,
    /** The user typed while listening: their edit wins, so listening stops. */
    val onTyped: () -> Unit,
)

/** Where the user types. While a run works, what they send is added to it rather than starting over. */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendLabel: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** When set, an empty composer offers a mic instead of send. */
    voice: ComposerVoice? = null,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val canSend = enabled && value.isNotBlank() && voice?.listening != true
    val send = { if (canSend) { haptics.perform(WhittleHaptic.Send); onSend() } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WhittleSize.composerMin)
            .background(colors.surfaceContainerHigh, RoundedCornerShape(WhittleRadius.xl))
            .padding(start = WhittleSpace.l, end = WhittleSpace.xs, top = WhittleSpace.xs, bottom = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val listening = voice?.listening == true
        Box(Modifier.weight(1f).padding(vertical = WhittleSpace.s), contentAlignment = Alignment.CenterStart) {
            when {
                value.isNotEmpty() -> Unit
                // Nothing heard yet: the wave stands in for the placeholder.
                listening -> VoiceWaveform(
                    level = voice.level,
                    color = colors.primary,
                    modifier = Modifier.fillMaxWidth().height(WAVE_HEIGHT).semantics { contentDescription = voice.listeningPlaceholder },
                )
                else -> Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = { typed ->
                    if (listening) voice?.onTyped?.invoke()
                    onValueChange(typed)
                },
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                // While the wave stands in for the placeholder, a cursor would blink over it.
                cursorBrush = SolidColor(if (listening && value.isEmpty()) Color.Transparent else colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = COMPOSER_MAX_LINES,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
                    // Hardware keyboards: Enter sends, Shift+Enter starts a new line.
                    .onPreviewKeyEvent { event ->
                        val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (!isEnter || event.isShiftPressed) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) send()
                        true
                    },
            )
        }
        if (voice != null && (value.isBlank() || voice.listening)) {
            // Words are coming in: the wave moves beside the stop button, out of their way.
            if (listening && value.isNotEmpty()) {
                VoiceWaveform(voice.level, colors.primary, Modifier.padding(horizontal = WhittleSpace.s).width(WAVE_COMPACT_WIDTH).height(WAVE_HEIGHT))
            }
            VoiceButton(voice, enabled)
            return@Row
        }
        FilledIconButton(
            onClick = send,
            enabled = canSend,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.size(WhittleSize.touchTarget - WhittleSpace.xs).semantics { contentDescription = sendLabel },
        ) {
            WhittleIcon("arrow_upward", size = WhittleSize.Icon.xl)
        }
    }
}

private const val QUARTER_TURN = 90f
private const val COMPOSER_MAX_LINES = 6

/** The mic: tonal while idle, a filled stop button while listening. */
@Composable
private fun VoiceButton(voice: ComposerVoice, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    FilledIconButton(
        onClick = { haptics.perform(if (voice.listening) WhittleHaptic.Tap else WhittleHaptic.Select); voice.onToggle() },
        enabled = enabled,
        shape = CircleShape,
        colors = if (voice.listening) {
            IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
        } else {
            IconButtonDefaults.filledIconButtonColors(containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer)
        },
        modifier = Modifier.size(WhittleSize.touchTarget - WhittleSpace.xs).semantics { contentDescription = if (voice.listening) voice.stopLabel else voice.startLabel },
    ) {
        WhittleIcon(if (voice.listening) "stop" else "mic", fill = voice.listening, size = WhittleSize.Icon.xl)
    }
}

/**
 * The voice as it's heard: one bar per moment, newest on the right, each as tall as the voice was loud then,
 * so the wave scrolls with the user's speech. A soft ripple keeps it alive through pauses. With animations
 * off in Android's settings it holds still.
 */
@Composable
private fun VoiceWaveform(level: Float, color: Color, modifier: Modifier) {
    val context = LocalContext.current
    val animate = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val heard by rememberUpdatedState(level)
    val history = remember { FloatArray(WAVE_SAMPLES) }
    var head by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(0L) }
    if (animate) {
        LaunchedEffect(Unit) {
            var sampledAt = 0L
            var smoothed = 0f
            while (true) {
                withFrameMillis { frame ->
                    now = frame
                    // The recognizer reports loudness in steps; easing toward it keeps the bars from jittering.
                    smoothed += (heard - smoothed) * WAVE_EASING
                    if (frame - sampledAt >= WAVE_STEP_MILLIS) {
                        history[head] = smoothed
                        head = (head + 1) % WAVE_SAMPLES
                        sampledAt = frame
                    }
                }
            }
        }
    }
    Canvas(modifier) {
        val bar = WAVE_BAR.toPx()
        val pitch = bar + WAVE_GAP.toPx()
        val count = ((size.width + WAVE_GAP.toPx()) / pitch).toInt().coerceIn(1, WAVE_SAMPLES)
        val left = size.width - count * pitch + WAVE_GAP.toPx()
        val radius = CornerRadius(bar / 2)
        val newest = head
        for (index in 0 until count) {
            val sample = history[(newest - count + index + WAVE_SAMPLES) % WAVE_SAMPLES]
            val ripple = WAVE_IDLE * (0.5f + 0.5f * sin(2 * PI.toFloat() * now / WAVE_IDLE_PERIOD_MILLIS + index * WAVE_IDLE_SPREAD))
            val height = max(bar, (ripple + sample * (1f - ripple)) * size.height)
            drawRoundRect(color, Offset(left + index * pitch, (size.height - height) / 2), Size(bar, height), radius)
        }
    }
}

/** The wave keeps this many moments, enough to fill the widest composer; at [WAVE_STEP_MILLIS] each, about six seconds. */
private const val WAVE_SAMPLES = 96
private const val WAVE_STEP_MILLIS = 60L
private const val WAVE_EASING = 0.35f

/** Height of the ripple in silence, as a share of the wave's height, and how it travels along the bars. */
private const val WAVE_IDLE = 0.3f
private const val WAVE_IDLE_PERIOD_MILLIS = 1400f
private const val WAVE_IDLE_SPREAD = 0.55f
private val WAVE_BAR = 3.dp
private val WAVE_GAP = 3.dp
private val WAVE_HEIGHT = 24.dp
private val WAVE_COMPACT_WIDTH = 72.dp
