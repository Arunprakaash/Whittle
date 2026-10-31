package app.whittle.connect

import app.whittle.design.window.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import app.whittle.design.components.ReadingScrollColumn
import app.whittle.design.components.ScreenHeading
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** Claude Code as a provider choice: its name, and whether it's the one in use. */
data class ClaudeCodeChoice(val name: String, val selected: Boolean)

/**
 * The ways to bring a model, one row each: the API key providers, then Claude Code. The [selected] provider is
 * ticked; Claude Code opens its own setup, so it shows a chevron until it's the one in use.
 */
@Composable
fun ProviderChoices(
    providers: List<ProviderEntry>,
    selected: String?,
    claudeCode: ClaudeCodeChoice?,
    onProvider: (String) -> Unit,
    onClaudeCode: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val count = providers.size + if (claudeCode != null) 1 else 0
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        providers.forEachIndexed { index, provider ->
            ChoiceRow(
                icon = KEY_ICON,
                name = provider.displayName,
                detail = provider.tagline.orEmpty(),
                selected = provider.id == selected,
                trailing = if (provider.id == selected) CHECK_ICON else null,
                index = index,
                count = count,
                enabled = enabled,
            ) { onProvider(provider.id) }
        }
        claudeCode?.let { choice ->
            ChoiceRow(
                icon = CLAUDE_CODE_ICON,
                name = choice.name,
                detail = stringResource(Res.string.claude_code_option_detail),
                selected = choice.selected,
                trailing = if (choice.selected) CHECK_ICON else CHEVRON_ICON,
                index = count - 1,
                count = count,
                enabled = enabled,
                onClick = onClaudeCode,
            )
        }
    }
}

/** Changing how the model is brought: the same choices as the first run, with the one in use ticked. */
@Composable
fun BringModelScreen(
    providers: List<ProviderEntry>,
    selected: String?,
    claudeCode: ClaudeCodeChoice?,
    onProvider: (String) -> Unit,
    onClaudeCode: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s)) {
                TopBarButton("arrow_back", stringResource(Res.string.connect_back), onBack)
            }
        },
    ) { padding ->
        // On a wide window the page reads in a centred column; the whole width still scrolls.
        ReadingScrollColumn(
            minInset = WhittleSpace.l,
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl),
        ) {
            ScreenHeading(stringResource(Res.string.bring_model_title), stringResource(Res.string.bring_model_body), Modifier.padding(horizontal = WhittleSpace.s))
            ProviderChoices(providers, selected, claudeCode, onProvider, onClaudeCode)
        }
    }
}

/** One way to bring a model, or to sign in: an icon tile, a name and a line about it, in a segmented list. */
@Composable
fun ChoiceRow(
    icon: String,
    name: String,
    detail: String,
    selected: Boolean,
    trailing: String?,
    index: Int,
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(
        Modifier
            .fillMaxWidth()
            .clip(segmentedShape(index, count))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { haptics.perform(WhittleHaptic.Select); onClick() }
            .padding(start = WhittleSpace.m, top = WhittleSpace.m, end = WhittleSpace.l, bottom = WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(
            Modifier.size(WhittleSize.Tile.m).background(if (selected) colors.surface else colors.secondaryContainer, RoundedCornerShape(WhittleRadius.m)),
            contentAlignment = Alignment.Center,
        ) {
            WhittleIcon(icon, size = WhittleSize.Icon.l, tint = colors.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        trailing?.let { WhittleIcon(it, size = WhittleSize.Icon.l, tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant) }
    }
}

private const val KEY_ICON = "key"
private const val CLAUDE_CODE_ICON = "terminal"
private const val CHECK_ICON = "check"
private const val CHEVRON_ICON = "chevron_right"
