package app.whittle.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleMotion
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import kotlinx.coroutines.delay

/** A page an answer cites, as the user sees it. */
@Immutable
data class SourceUi(val title: String, val host: String, val url: String)

/**
 * Under an answer: a pill with the cited sites' icons and a count ("3 sources"); tapping it lists each
 * page, and tapping a page opens it.
 */
@Composable
fun AnswerSources(
    label: String,
    sources: List<SourceUi>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (SourceUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        SourcesPill(label, sources.map { it.host }.distinct().take(STACKED_ICONS), expanded, onToggle)
        AnimatedVisibility(expanded) { SourceList(sources, onOpen) }
    }
}

/** What an answer's footer offers: copying it, and the pages it cites when there are any. */
@Immutable
data class AnswerFooterUi(
    val copyLabel: String,
    val copiedLabel: String,
    val sourcesLabel: String?,
    val sources: List<SourceUi>,
    val sourcesExpanded: Boolean,
)

/** Under an answer: a copy button and, when the answer cites pages, the sources pill on the same row. */
@Composable
fun AnswerFooter(
    footer: AnswerFooterUi,
    onCopy: () -> Unit,
    onToggleSources: () -> Unit,
    onOpenSource: (SourceUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            CopyButton(footer.copyLabel, footer.copiedLabel, onCopy)
            if (footer.sourcesLabel != null && footer.sources.isNotEmpty()) {
                SourcesPill(footer.sourcesLabel, footer.sources.map { it.host }.distinct().take(STACKED_ICONS), footer.sourcesExpanded, onToggleSources)
            }
        }
        AnimatedVisibility(footer.sourcesExpanded && footer.sources.isNotEmpty()) { SourceList(footer.sources, onOpenSource) }
    }
}

/** Copies, then shows a check for a moment so the tap visibly landed. */
@Composable
private fun CopyButton(label: String, copiedLabel: String, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MILLIS)
            copied = false
        }
    }
    Box(
        modifier = Modifier
            .size(WhittleSize.Tile.s)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) {
                haptics.perform(WhittleHaptic.Tap)
                onCopy()
                copied = true
            }
            .semantics { if (copied) stateDescription = copiedLabel },
        contentAlignment = Alignment.Center,
    ) {
        WhittleIcon(
            if (copied) "check" else "content_copy",
            contentDescription = if (copied) copiedLabel else label,
            size = WhittleSize.Icon.m,
            tint = if (copied) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourcesPill(label: String, hosts: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val chevron by animateFloatAsState(if (expanded) HALF_TURN else 0f, WhittleMotion.spatialDefault.spec(), label = "sourcesChevron")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(WhittleRadius.full))
            .background(colors.surfaceContainerLow)
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onToggle() }
            .padding(start = WhittleSpace.xs, top = WhittleSpace.xs, end = WhittleSpace.m, bottom = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        StackedSiteIcons(hosts, ringColor = colors.surfaceContainerLow)
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        WhittleIcon("expand_more", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(chevron))
    }
}

/** Overlapping discs, first site on top, each ringed in the pill's color so the overlap reads cleanly. */
@Composable
private fun StackedSiteIcons(hosts: List<String>, ringColor: Color) {
    val disc = WhittleSize.Icon.xl
    val step = disc - WhittleSpace.s
    Box(Modifier.size(width = step * (hosts.size - 1) + disc, height = disc)) {
        hosts.forEachIndexed { index, host ->
            Box(
                Modifier
                    .padding(start = step * index)
                    .zIndex((hosts.size - index).toFloat())
                    .border(WhittleSpace.hairline, ringColor, CircleShape),
            ) {
                SiteIcon(host, containerSize = disc, iconSize = WhittleSize.Icon.s) { FallbackDisc(disc, WhittleSize.Icon.s) }
            }
        }
    }
}

@Composable
private fun SourceList(sources: List<SourceUi>, onOpen: (SourceUi) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        sources.forEachIndexed { index, source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(segmentedShape(index, sources.size))
                    .background(colors.surfaceContainerLow)
                    .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onOpen(source) }
                    .padding(start = WhittleSpace.s, top = WhittleSpace.s, end = WhittleSpace.m, bottom = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                SiteIcon(source.host, containerSize = WhittleSize.Tile.s, iconSize = WhittleSize.Icon.l) {
                    FallbackDisc(WhittleSize.Tile.s, WhittleSize.Icon.m)
                }
                Column(Modifier.weight(1f)) {
                    Text(source.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(source.host, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                }
                WhittleIcon("open_in_new", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** Shown while a site's icon loads, or when it has none. */
@Composable
private fun FallbackDisc(size: Dp, iconSize: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(size).background(colors.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        WhittleIcon("language", size = iconSize, tint = colors.onSecondaryContainer)
    }
}

private const val STACKED_ICONS = 3
private const val COPIED_FEEDBACK_MILLIS = 1_500L
private const val HALF_TURN = 180f
