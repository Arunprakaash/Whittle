package app.whittle.connect

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

@Composable
fun ConnectScreen(state: ConnectState, viewModel: ConnectViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = WhittleSpace.xxl, vertical = WhittleSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
    ) {
        when (val phase = state.phase) {
            is ConnectPhase.ChooseModel -> ChooseModel(phase, viewModel)
            else -> EnterKey(state, phase, viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EnterKey(state: ConnectState, phase: ConnectPhase, viewModel: ConnectViewModel) {
    val haptics = WhittleTheme.haptics
    var keyVisible by remember { mutableStateOf(false) }
    val checking = phase == ConnectPhase.Checking
    Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.connect_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(WhittleSpace.s))
    Text(stringResource(R.string.connect_provider), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        state.providers.forEachIndexed { index, provider ->
            SegmentedButton(
                selected = provider.id == state.providerId,
                onClick = { haptics.perform(WhittleHaptic.Select); viewModel.selectProvider(provider.id) },
                shape = SegmentedButtonDefaults.itemShape(index, state.providers.size),
                enabled = !checking,
            ) { Text(provider.displayName) }
        }
    }
    OutlinedTextField(
        value = state.key,
        onValueChange = viewModel::updateKey,
        label = { Text(stringResource(R.string.connect_key_label)) },
        singleLine = true,
        enabled = !checking,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { viewModel.connect() }),
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                WhittleIcon(
                    if (keyVisible) "visibility_off" else "visibility",
                    contentDescription = stringResource(if (keyVisible) R.string.connect_key_hide else R.string.connect_key_show),
                )
            }
        },
        isError = (phase as? ConnectPhase.EnterKey)?.error != null,
        supportingText = (phase as? ConnectPhase.EnterKey)?.error?.let { error -> { Text(errorText(error, state.provider.displayName)) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { haptics.perform(WhittleHaptic.Tap); viewModel.connect() },
        shapes = ButtonDefaults.shapes(),
        enabled = state.key.isNotBlank() && !checking,
        modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.default),
    ) {
        if (checking) {
            CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline, color = MaterialTheme.colorScheme.onPrimary)
            Text(stringResource(R.string.connect_checking), Modifier.padding(start = WhittleSpace.s))
        } else {
            WhittleIcon("key", size = WhittleSize.Icon.m)
            Text(stringResource(R.string.connect_action), Modifier.padding(start = WhittleSpace.s))
        }
    }
}

@Composable
private fun ChooseModel(phase: ConnectPhase.ChooseModel, viewModel: ConnectViewModel) {
    val haptics = WhittleTheme.haptics
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = viewModel::backToKey) {
            WhittleIcon("arrow_back", contentDescription = stringResource(R.string.connect_back))
        }
        Text(stringResource(R.string.connect_choose_model), style = MaterialTheme.typography.headlineSmall)
    }
    val search = rememberTextFieldState(phase.query)
    LaunchedEffect(search) { snapshotFlow { search.text.toString() }.collect(viewModel::updateQuery) }
    WhittleSearchBar(search, placeholder = stringResource(R.string.connect_search_models), clearLabel = stringResource(R.string.search_clear))
    val models = phase.visible
    if (models.isEmpty()) {
        Text(stringResource(R.string.connect_no_models), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        itemsIndexed(models, key = { _, model -> model.id.value }) { index, model ->
            ModelRow(model, segmentedShape(index, models.size)) { haptics.perform(WhittleHaptic.Approve); viewModel.choose(model) }
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
