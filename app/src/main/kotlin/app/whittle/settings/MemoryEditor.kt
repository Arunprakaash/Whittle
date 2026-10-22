package app.whittle.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.core.common.Outcome
import app.whittle.design.components.MenuAction
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleFilledField
import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.observability.posthog.userContent
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Writes a new memory or changes one, full screen: close and Save at the top, one field with a counter, when it was
 * saved and that it stays on the phone. Forget this is in the top bar's menu.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MemoryEditor(graph: AppGraph, draft: MemoryDraft, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val policy = graph.memory.policy
    val saved = draft.memory
    var text by remember { mutableStateOf(draft.text) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showingMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val canSave = !saving && text.isNotBlank() && text.trim() != saved?.text
    val saveFailed = stringResource(R.string.memory_save_failed)
    val fullText = stringResource(R.string.memory_full)

    fun save() {
        saving = true
        error = null
        scope.launch {
            val result = if (saved == null) graph.memory.remember(text) else graph.memory.change(saved.id, text)
            when (result) {
                is Outcome.Success -> onClose()
                // The store's reasons are written for the model; the user gets their own words.
                is Outcome.Failure -> error = if (saved == null && graph.memory.current().size >= policy.maxItems) fullText else saveFailed
            }
            saving = false
        }
    }

    Scaffold(
        containerColor = colors.surface,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (scrolled) colors.surfaceContainerLow else colors.surface)
                    .statusBarsPadding()
                    .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
            ) {
                TopBarButton("close", stringResource(R.string.memory_close), onClose)
                Text(
                    stringResource(if (saved == null) R.string.memory_new else R.string.memory_edit),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { haptics.perform(WhittleHaptic.Approve); save() },
                    enabled = canSave,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.height(WhittleSize.Button.small),
                ) { Text(stringResource(R.string.memory_save)) }
                if (saved != null) {
                    Box {
                        TopBarButton("more_vert", stringResource(R.string.memory_more), { showingMenu = true })
                        WhittleMenu(
                            expanded = showingMenu,
                            onDismiss = { showingMenu = false },
                            groups = listOf(
                                listOf(
                                    MenuAction(stringResource(R.string.memory_forget), "delete", {
                                        graph.appScope.launch { graph.memory.forget(saved.id) }
                                        onClose()
                                    }, destructive = true),
                                ),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scroll)
                .padding(start = WhittleSpace.l, end = WhittleSpace.l, top = WhittleSpace.s, bottom = WhittleSpace.xxxl),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl),
        ) {
            WhittleFilledField(
                value = text,
                onValueChange = { text = it.take(policy.maxItemChars); error = null },
                label = stringResource(R.string.memory_field),
                minLines = FIELD_LINES,
                enabled = !saving,
                hint = stringResource(R.string.memory_field_hint),
                counter = stringResource(R.string.memory_counter, text.length, policy.maxItemChars),
                error = error,
                autoFocus = saved == null,
                modifier = Modifier.userContent(),
            )
            val facts = listOfNotNull(
                saved?.let {
                    Triple("history", stringResource(R.string.memory_saved_at, savedOn(it.savedAtMillis)), stringResource(R.string.memory_saved_note))
                },
                Triple("lock", stringResource(R.string.memory_private), stringResource(R.string.memory_private_note)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                facts.forEachIndexed { index, (icon, title, subtitle) ->
                    SettingsRow(icon = icon, title = title, subtitle = subtitle, shape = segmentedShape(index, facts.size))
                }
            }
        }
    }
}

/** The date and time a memory was saved, in the phone's style. */
private fun savedOn(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** How tall the field starts, in lines. */
private const val FIELD_LINES = 3
