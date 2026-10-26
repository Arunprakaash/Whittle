package app.whittle.chat.ui

import app.whittle.files.ui.nowMillis
import app.whittle.files.ui.timeAgo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** Above the composer: the project the chat works on, or the whole workspace. One line; a long name is shortened. */
@Composable
fun ProjectChip(project: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val (container, content) = if (project != null) colors.secondaryContainer to colors.onSecondaryContainer else colors.surfaceContainerHigh to colors.onSurfaceVariant
    val label = project ?: stringResource(Res.string.project_whole)
    Row(
        modifier
            .height(WhittleSize.Tile.s)
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.project_choose)) { haptics.perform(WhittleHaptic.Tap); onClick() }
            .padding(start = WhittleSpace.s, end = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
    ) {
        WhittleIcon(if (project != null) "folder" else "workspaces", size = WhittleSize.Icon.m, tint = content)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        WhittleIcon("expand_more", size = WhittleSize.Icon.m, tint = content)
    }
}

/** A new chat's suggestions: the most recently changed projects, to carry one on in a tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarryOn(projects: List<ProjectEntry>, onPick: (String) -> Unit) {
    if (projects.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Column(Modifier.padding(top = WhittleSpace.l), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        Text(stringResource(Res.string.project_carry_on), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            projects.forEach { project ->
                Row(
                    Modifier
                        .height(WhittleSize.Button.small)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerLow)
                        .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onPick(project.name) }
                        .padding(start = WhittleSpace.m, end = WhittleSpace.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
                ) {
                    WhittleIcon("folder", size = WhittleSize.Icon.m, tint = colors.primary)
                    Text(project.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Picks the chat's project: the whole workspace, any project by when it last changed, or a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSheet(current: String?, projects: List<ProjectEntry>, onChoose: (String?) -> Unit, onCreate: suspend (String) -> Boolean, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var naming by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(
            Modifier.padding(horizontal = WhittleSpace.l).navigationBarsPadding().padding(bottom = WhittleSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
        ) {
            Column(Modifier.padding(horizontal = WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
                Text(stringResource(Res.string.project_sheet_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.project_sheet_body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            val now = nowMillis()
            val choices = listOf(
                Choice(null, "workspaces", stringResource(Res.string.project_whole), stringResource(Res.string.project_whole_meta)),
            ) + projects.map { project ->
                val ago = timeAgo(project.changedAtMillis, now)
                Choice(project.name, "folder", project.name, stringResource(Res.string.project_changed, ago))
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                choices.forEachIndexed { index, choice ->
                    ChoiceRow(choice, selected = choice.project == current, shape = segmentedShape(index, choices.size)) { onChoose(choice.project); onDismiss() }
                }
            }
            FilledTonalButton(onClick = { naming = true }, modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.default)) {
                WhittleIcon("create_new_folder", size = WhittleSize.Icon.l)
                Spacer(Modifier.width(WhittleSpace.s))
                Text(stringResource(Res.string.project_new), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (naming) NewProjectDialog(onCreate = { name -> onCreate(name).also { made -> if (made) { naming = false; onDismiss() } } }, onDismiss = { naming = false })
}

private data class Choice(val project: String?, val icon: String, val name: String, val meta: String)

@Composable
private fun ChoiceRow(choice: Choice, selected: Boolean, shape: RoundedCornerShape, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
            .selectable(selected = selected, role = Role.RadioButton) { haptics.perform(WhittleHaptic.Select); onClick() }
            .padding(start = WhittleSpace.m, top = WhittleSpace.m, end = WhittleSpace.l, bottom = WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(
            Modifier.size(WhittleSize.Tile.m).background(if (selected) colors.surface else colors.surfaceContainerHigh, RoundedCornerShape(WhittleRadius.m)),
            contentAlignment = Alignment.Center,
        ) { WhittleIcon(choice.icon, size = WhittleSize.Icon.l, tint = colors.onSurface) }
        Column(Modifier.weight(1f)) {
            Text(choice.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(choice.meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WhittleIcon(
            if (selected) "radio_button_checked" else "radio_button_unchecked",
            size = WhittleSize.Icon.xl,
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun NewProjectDialog(onCreate: suspend (String) -> Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var refused by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.project_new)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it; refused = false },
                label = { Text(stringResource(Res.string.project_new_hint)) },
                singleLine = true,
                isError = refused,
                supportingText = if (refused) { { Text(stringResource(Res.string.project_new_invalid)) } } else null,
                shape = RoundedCornerShape(WhittleRadius.l),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { scope.launch { refused = !onCreate(name) } }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.project_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.project_cancel)) } },
    )
}
