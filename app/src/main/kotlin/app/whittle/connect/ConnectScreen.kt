package app.whittle.connect

import android.content.ActivityNotFoundException
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
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
import androidx.activity.compose.BackHandler
import app.whittle.observability.posthog.hiddenFromReplay
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.R
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleSearchBar
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.llm.api.ModelDescriptor

/** Connecting the first model. [header] sits above it: on first run, Linux's setup and the step bar. */
@Composable
fun ConnectScreen(
    state: ConnectState,
    viewModel: ConnectViewModel,
    header: @Composable ColumnScope.() -> Unit = {},
    /** Claude Code's name when it's offered as a way to bring a model; [onClaudeCode] opens its setup. */
    claudeCode: String? = null,
    onClaudeCode: () -> Unit = {},
) {
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
            is ConnectPhase.ChooseModel -> Column(
                Modifier.weight(1f).padding(vertical = WhittleSpace.l),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
            ) {
                PickerTitle(viewModel::backToKey)
                ChooseModel(phase, viewModel, onChoose = { viewModel.choose(it) })
            }
            else -> EnterKey(state, phase, viewModel, claudeCode, onClaudeCode)
        }
    }
}

/** Changing the model: the connected provider's models, listed with its saved key; Use another opens the choices. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModelPickerScreen(
    state: ConnectState,
    viewModel: ConnectViewModel,
    onClose: () -> Unit,
    onUseAnother: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val haptics = WhittleTheme.haptics
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        // The same top bar as Settings and the other screens it opens.
        PickerTitle(onClose, Modifier.padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s))
        Column(
            Modifier.weight(1f).padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
        ) {
            val switchProvider: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.provider.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onUseAnother) { Text(stringResource(R.string.model_picker_other_provider)) }
                }
            }
            when (val phase = state.phase) {
                is ConnectPhase.ChooseModel -> ChooseModel(phase, viewModel, onChoose = { viewModel.choose(it, onClose) }, header = switchProvider)
                ConnectPhase.Checking -> {
                    switchProvider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                        CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline)
                        Text(stringResource(R.string.model_picker_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is ConnectPhase.LoadFailed -> {
                    switchProvider()
                    Text(errorText(phase.error, state.provider.displayName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { haptics.perform(WhittleHaptic.Tap); viewModel.retry() }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.model_picker_retry))
                    }
                }
                // The saved key is missing: ask for it, as when connecting.
                is ConnectPhase.EnterKey -> EnterKey(state, phase, viewModel)
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
        TopBarButton("arrow_back", stringResource(R.string.connect_back), onBack)
        Text(stringResource(R.string.connect_choose_model), style = MaterialTheme.typography.titleLarge)
    }
}

/** Provider and key: a connected pair to pick the provider, a filled field for the key, Connect at the bottom. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.EnterKey(
    state: ConnectState,
    phase: ConnectPhase,
    viewModel: ConnectViewModel,
    claudeCode: String? = null,
    onClaudeCode: () -> Unit = {},
) {
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val checking = phase == ConnectPhase.Checking
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl)) {
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.connect_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            Text(stringResource(R.string.connect_provider), style = MaterialTheme.typography.labelLarge)
            ProviderChoices(
                providers = state.providers,
                selected = state.providerId,
                claudeCode = claudeCode?.let { ClaudeCodeChoice(it, selected = false) },
                onProvider = viewModel::selectProvider,
                onClaudeCode = onClaudeCode,
                enabled = !checking,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            KeyField(state, phase, viewModel)
            state.provider.keysUrl?.let { url ->
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            try {
                                uriHandler.openUri(url)
                            } catch (exception: ActivityNotFoundException) {
                                // No browser on the phone; the link has nowhere to go.
                            }
                        }
                        .padding(WhittleSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
                ) {
                    Text(stringResource(R.string.connect_get_key, state.provider.displayName), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    WhittleIcon("open_in_new", size = WhittleSize.Icon.m, tint = colors.primary)
                }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    Button(
        onClick = { haptics.perform(WhittleHaptic.Tap); viewModel.connect() },
        shapes = ButtonDefaults.shapes(),
        enabled = state.key.isNotBlank() && !checking,
        modifier = Modifier.padding(top = WhittleSpace.l, bottom = WhittleSpace.xxl).fillMaxWidth().height(WhittleSize.Button.large),
    ) {
        if (checking) {
            CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline, color = colors.onPrimary)
            Spacer(Modifier.size(WhittleSpace.s))
            Text(stringResource(R.string.connect_checking), style = MaterialTheme.typography.titleMedium)
        } else {
            WhittleIcon("key", size = WhittleSize.Icon.l)
            Spacer(Modifier.size(WhittleSpace.s))
            Text(stringResource(R.string.connect_action), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The key: a filled, rounded field like the search bar, its label on top (green while typing, red when refused), the key in mono. */
@Composable
private fun KeyField(state: ConnectState, phase: ConnectPhase, viewModel: ConnectViewModel) {
    val error = (phase as? ConnectPhase.EnterKey)?.error
    SecretField(
        value = state.key,
        onValueChange = viewModel::updateKey,
        label = stringResource(R.string.connect_key_label),
        enabled = phase != ConnectPhase.Checking,
        isError = error != null,
        onDone = viewModel::connect,
    )
    error?.let {
        Text(
            errorText(it, state.provider.displayName),
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
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pasteLabel = stringResource(R.string.connect_key_paste)
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
        modifier = Modifier.fillMaxWidth().hiddenFromReplay(),
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
                                clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
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
                        contentDescription = stringResource(if (keyVisible) R.string.connect_key_hide else R.string.connect_key_show),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun ChooseModel(
    phase: ConnectPhase.ChooseModel,
    viewModel: ConnectViewModel,
    onChoose: (ModelDescriptor) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val haptics = WhittleTheme.haptics
    header?.invoke()
    val search = rememberTextFieldState(phase.query)
    LaunchedEffect(search) { snapshotFlow { search.text.toString() }.collect(viewModel::updateQuery) }
    WhittleSearchBar(search, placeholder = stringResource(R.string.connect_search_models), clearLabel = stringResource(R.string.search_clear))
    val models = phase.visible
    if (models.isEmpty()) {
        Text(stringResource(R.string.connect_no_models), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        itemsIndexed(models, key = { _, model -> model.id.value }) { index, model ->
            ModelRow(model, segmentedShape(index, models.size)) { haptics.perform(WhittleHaptic.Approve); onChoose(model) }
        }
    }
}

@Composable
private fun ModelRow(model: ModelDescriptor, shape: RoundedCornerShape, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
    ) {
        Text(model.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (model.displayName != model.id.value) {
            Text(model.id.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun errorText(error: ConnectError, provider: String): String = when (error) {
    ConnectError.KeyRejected -> stringResource(R.string.error_key_rejected, provider)
    ConnectError.Unreachable -> stringResource(R.string.error_unreachable, provider)
    is ConnectError.ProviderFailed -> stringResource(R.string.error_provider, provider, error.statusCode)
    ConnectError.StorageFailed -> stringResource(R.string.error_storage)
    ConnectError.Unknown -> stringResource(R.string.error_generic)
}
