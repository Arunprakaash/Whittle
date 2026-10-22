package app.whittle.connect

import android.content.ActivityNotFoundException
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.platform.LocalUriHandler
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
fun ConnectScreen(state: ConnectState, viewModel: ConnectViewModel, header: @Composable ColumnScope.() -> Unit = {}) {
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
            ) { ChooseModel(phase, viewModel, onBack = viewModel::backToKey, onChoose = { viewModel.choose(it) }) }
            else -> EnterKey(state, phase, viewModel)
        }
    }
}

/**
 * Changing the model: the connected provider's models, listed with its saved key. Using another provider
 * forgets this one's key, after asking.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModelPickerScreen(state: ConnectState, viewModel: ConnectViewModel, onClose: () -> Unit, onSwitchProvider: () -> Unit) {
    BackHandler(onBack = onClose)
    var confirming by remember { mutableStateOf(false) }
    val haptics = WhittleTheme.haptics
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = WhittleSpace.xxl, vertical = WhittleSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
    ) {
        val switchProvider: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.provider.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.model_picker_other_provider)) }
            }
        }
        when (val phase = state.phase) {
            is ConnectPhase.ChooseModel -> ChooseModel(phase, viewModel, onBack = onClose, onChoose = { viewModel.choose(it, onClose) }, header = switchProvider)
            ConnectPhase.Checking -> {
                PickerTitle(onClose)
                switchProvider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline)
                    Text(stringResource(R.string.model_picker_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }
            is ConnectPhase.LoadFailed -> {
                PickerTitle(onClose)
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
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.model_picker_switch_title)) },
            text = { Text(stringResource(R.string.model_picker_switch_body, state.provider.displayName)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onSwitchProvider() }) { Text(stringResource(R.string.model_picker_switch_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.model_picker_cancel)) } },
        )
    }
}

@Composable
private fun PickerTitle(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            WhittleIcon("arrow_back", contentDescription = stringResource(R.string.connect_back))
        }
        Text(stringResource(R.string.connect_choose_model), style = MaterialTheme.typography.headlineSmall)
    }
}

/** Provider and key: a connected pair to pick the provider, a filled field for the key, Connect at the bottom. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.EnterKey(state: ConnectState, phase: ConnectPhase, viewModel: ConnectViewModel) {
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
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                state.providers.forEachIndexed { index, provider ->
                    val checked = provider.id == state.providerId
                    val content = if (checked) colors.onSecondaryContainer else colors.onSurface
                    Row(
                        Modifier
                            .weight(1f)
                            .height(WhittleSize.Button.default)
                            .clip(connectedShape(index, state.providers.size))
                            .background(if (checked) colors.secondaryContainer else colors.surfaceContainerHigh)
                            .selectable(selected = checked, enabled = !checking, role = Role.RadioButton) {
                                haptics.perform(WhittleHaptic.Select)
                                viewModel.selectProvider(provider.id)
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (checked) {
                            WhittleIcon("check", size = WhittleSize.Icon.l, tint = content)
                            Spacer(Modifier.size(WhittleSpace.s))
                        }
                        Text(provider.displayName, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
                    }
                }
            }
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
    val colors = MaterialTheme.colorScheme
    var keyVisible by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val error = (phase as? ConnectPhase.EnterKey)?.error
    val accent = when {
        error != null -> colors.error
        focused -> colors.primary
        else -> colors.onSurfaceVariant
    }
    BasicTextField(
        value = state.key,
        onValueChange = viewModel::updateKey,
        enabled = phase != ConnectPhase.Checking,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = WhittleFonts.mono, color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { viewModel.connect() }),
        interactionSource = interaction,
        // The key never appears in a session recording, even when shown or when recordings show content.
        modifier = Modifier.fillMaxWidth().hiddenFromReplay(),
        decorationBox = { input ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(WhittleSize.composerMin)
                    .clip(RoundedCornerShape(WhittleRadius.l))
                    .background(colors.surfaceContainerHigh)
                    .padding(start = WhittleSpace.l, end = WhittleSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.connect_key_label), style = MaterialTheme.typography.labelMedium, color = accent)
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
    error?.let {
        Text(errorText(it, state.provider.displayName), style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(start = WhittleSpace.l))
    }
}

@Composable
private fun ChooseModel(
    phase: ConnectPhase.ChooseModel,
    viewModel: ConnectViewModel,
    onBack: () -> Unit,
    onChoose: (ModelDescriptor) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val haptics = WhittleTheme.haptics
    PickerTitle(onBack)
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
