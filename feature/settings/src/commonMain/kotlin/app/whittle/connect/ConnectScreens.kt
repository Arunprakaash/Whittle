package app.whittle.connect

import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import app.whittle.design.window.WindowTopBar
import app.whittle.design.window.LocalWindowChrome
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.collectAsState
import app.whittle.design.window.BackHandler
import app.whittle.design.window.plainText
import app.whittle.design.window.secretContent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import app.whittle.design.components.connectedShape
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.tokens.WhittleRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleSearchBar
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** Connecting the first model. [header] sits above it: on first run, Linux's setup and the step bar. */
@Composable
fun ConnectScreen(
    host: ModelPickerHost,
    header: @Composable ColumnScope.() -> Unit = {},
    /** Claude Code's name when it's offered as a way to bring a model; [onClaudeCode] opens its setup. */
    claudeCode: String? = null,
    onClaudeCode: () -> Unit = {},
) {
    val state by host.state.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = WhittleSpace.xxl),
    ) {
        header()
        when (val phase = state.phase) {
            is PickerPhase.ChooseModel -> Column(
                Modifier.weight(1f).padding(vertical = WhittleSpace.l),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
            ) {
                PickerTitle({ host.act(PickerAction.BackToKey) })
                ChooseModel(phase, host, onChoose = { host.act(PickerAction.Choose(it.id)) })
            }
            else -> EnterKey(state, phase, host, claudeCode, onClaudeCode)
        }
    }
}

/** Changing the model: the connected provider's models, listed with its saved key; Use another opens the choices. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModelPickerScreen(
    host: ModelPickerHost,
    onClose: () -> Unit,
    onUseAnother: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onClose)
    val state by host.state.collectAsState()
    val haptics = WhittleTheme.haptics
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        // The same top bar as Settings and the other screens it opens.
        WindowTopBar {
            PickerTitle(onClose, Modifier.windowInsetsPadding(LocalWindowChrome.current?.insets ?: WindowInsets(0)).padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s))
        }
        Column(
            Modifier.weight(1f).padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
        ) {
            val switchProvider: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.provider?.displayName.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onUseAnother) { Text(stringResource(Res.string.model_picker_other_provider)) }
                }
            }
            when (val phase = state.phase) {
                is PickerPhase.ChooseModel -> ChooseModel(phase, host, onChoose = { host.act(PickerAction.Choose(it.id)); onClose() }, header = switchProvider)
                PickerPhase.Checking -> {
                    switchProvider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                        CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline)
                        Text(stringResource(Res.string.model_picker_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is PickerPhase.LoadFailed -> {
                    switchProvider()
                    Text(errorText(phase.error, state.provider?.displayName.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { haptics.perform(WhittleHaptic.Tap); host.act(PickerAction.Retry) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.model_picker_retry))
                    }
                }
                // The saved key is missing: ask for it, as when connecting.
                is PickerPhase.EnterKey -> EnterKey(state, phase, host)
            }
        }
    }
}

/** Back on its own disc, then the title, as on Settings. */
@Composable
private fun PickerTitle(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        TopBarButton("arrow_back", stringResource(Res.string.connect_back), onBack)
        Text(stringResource(Res.string.connect_choose_model), style = MaterialTheme.typography.titleLarge)
    }
}

/** Provider and key: a connected pair to pick the provider, a filled field for the key, Connect at the bottom. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.EnterKey(
    state: PickerState,
    phase: PickerPhase,
    host: ModelPickerHost,
    claudeCode: String? = null,
    onClaudeCode: () -> Unit = {},
) {
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val checking = phase == PickerPhase.Checking
    // The key stays on the screen it's typed on until Connect sends it to be checked.
    var key by rememberSaveable { mutableStateOf("") }
    val connect = { if (key.isNotBlank() && !checking) host.act(PickerAction.Connect(key.trim())) }
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl)) {
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Text(stringResource(Res.string.connect_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(Res.string.connect_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            Text(stringResource(Res.string.connect_provider), style = MaterialTheme.typography.labelLarge)
            ProviderChoices(
                providers = state.providers,
                selected = state.providerId,
                claudeCode = claudeCode?.let { ClaudeCodeChoice(it, selected = false) },
                onProvider = { host.act(PickerAction.SelectProvider(it)) },
                onClaudeCode = onClaudeCode,
                enabled = !checking,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            KeyField(key, state, phase, onKey = { typed ->
                key = typed
                // A new key clears what was said about the last one.
                if ((phase as? PickerPhase.EnterKey)?.error != null) host.act(PickerAction.BackToKey)
            }, onDone = connect)
            state.provider?.keysUrl?.let { url ->
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            // With no browser to open it, the link has nowhere to go.
                            runCatching { uriHandler.openUri(url) }
                        }
                        .padding(WhittleSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
                ) {
                    Text(stringResource(Res.string.connect_get_key, state.provider?.displayName.orEmpty()), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    WhittleIcon("open_in_new", size = WhittleSize.Icon.m, tint = colors.primary)
                }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    Button(
        onClick = { haptics.perform(WhittleHaptic.Tap); connect() },
        shapes = ButtonDefaults.shapes(),
        enabled = key.isNotBlank() && !checking,
        modifier = Modifier.padding(top = WhittleSpace.l, bottom = WhittleSpace.xxl).fillMaxWidth().height(WhittleSize.Button.large),
    ) {
        if (checking) {
            CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline, color = colors.onPrimary)
            Spacer(Modifier.size(WhittleSpace.s))
            Text(stringResource(Res.string.connect_checking), style = MaterialTheme.typography.titleMedium)
        } else {
            WhittleIcon("key", size = WhittleSize.Icon.l)
            Spacer(Modifier.size(WhittleSpace.s))
            Text(stringResource(Res.string.connect_action), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The key: a filled, rounded field like the search bar, its label on top (green while typing, red when refused), the key in mono. */
@Composable
private fun KeyField(key: String, state: PickerState, phase: PickerPhase, onKey: (String) -> Unit, onDone: () -> Unit) {
    val error = (phase as? PickerPhase.EnterKey)?.error
    SecretField(
        value = key,
        onValueChange = onKey,
        label = stringResource(Res.string.connect_key_label),
        enabled = phase != PickerPhase.Checking,
        isError = error != null,
        onDone = onDone,
    )
    error?.let {
        Text(
            errorText(it, state.provider?.displayName.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = WhittleSpace.xl),
        )
    }
}

/**
 * A key the user pastes, as the sign-in code: a pill with paste at the start, the words inside, and show or hide at
 * the end. It never appears in a session recording, even when shown or when recordings show content.
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    isError: Boolean,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var keyVisible by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pasteLabel = stringResource(Res.string.connect_key_paste)
    val accent = when {
        isError -> colors.error
        focused -> colors.primary
        else -> colors.onSurfaceVariant
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = WhittleFonts.mono, color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().secretContent(),
        decorationBox = { input ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(WhittleSize.Button.default)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = WhittleSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(WhittleSize.Tile.m)
                        .clip(CircleShape)
                        .clickable(enabled = enabled, role = Role.Button) {
                            scope.launch {
                                clipboard.getClipEntry()?.plainText()?.trim()
                                    ?.let(onValueChange)
                            }
                        }
                        .semantics { contentDescription = pasteLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    WhittleIcon("content_paste", size = WhittleSize.Icon.l, tint = accent)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = accent, maxLines = 1)
                    input()
                }
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    WhittleIcon(
                        if (keyVisible) "visibility_off" else "visibility",
                        contentDescription = stringResource(if (keyVisible) Res.string.connect_key_hide else Res.string.connect_key_show),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun ChooseModel(
    phase: PickerPhase.ChooseModel,
    host: ModelPickerHost,
    onChoose: (ModelEntry) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val haptics = WhittleTheme.haptics
    header?.invoke()
    val search = rememberTextFieldState(phase.query)
    LaunchedEffect(search) { snapshotFlow { search.text.toString() }.collect { host.act(PickerAction.Query(it)) } }
    WhittleSearchBar(search, placeholder = stringResource(Res.string.connect_search_models), clearLabel = stringResource(Res.string.search_clear))
    val models = phase.visible
    if (models.isEmpty()) {
        Text(stringResource(Res.string.connect_no_models), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        itemsIndexed(models, key = { _, model -> model.id }) { index, model ->
            ModelRow(model, segmentedShape(index, models.size)) { haptics.perform(WhittleHaptic.Approve); onChoose(model) }
        }
    }
}

@Composable
private fun ModelRow(model: ModelEntry, shape: RoundedCornerShape, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
    ) {
        Text(model.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (model.displayName != model.id) {
            Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun errorText(error: PickerError, provider: String): String = when (error) {
    PickerError.KeyRejected -> stringResource(Res.string.error_key_rejected, provider)
    PickerError.Unreachable -> stringResource(Res.string.error_unreachable, provider)
    is PickerError.ProviderFailed -> stringResource(Res.string.error_provider, provider, error.statusCode)
    PickerError.StorageFailed -> stringResource(Res.string.error_storage)
    PickerError.Unknown -> stringResource(Res.string.error_generic)
}
