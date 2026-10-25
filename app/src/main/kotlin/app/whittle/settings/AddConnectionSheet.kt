package app.whittle.settings

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.connectors.mcp.HeaderSpec
import app.whittle.connectors.mcp.McpAuthMode
import app.whittle.connectors.mcp.McpConnection
import app.whittle.connectors.mcp.McpTransportKind
import app.whittle.connectors.mcp.OAuthClientCredentials
import app.whittle.core.common.Outcome
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.connectedShape
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.observability.posthog.hiddenFromReplay
import kotlinx.coroutines.launch

/** How a custom connection signs in, as the sheet offers it; Automatic lets Whittle check the server. */
private enum class SignInChoice(val title: Int, val body: Int, val auth: McpAuthMode?) {
    Automatic(R.string.connector_mode_auto, R.string.connector_mode_auto_body, null),
    Service(R.string.connector_mode_oauth, R.string.connector_mode_oauth_body, McpAuthMode.OAUTH),
    Key(R.string.connector_mode_key, R.string.connector_mode_key_body, McpAuthMode.TOKEN),
    None(R.string.connector_mode_none, R.string.connector_mode_none_body, McpAuthMode.NONE),
}

/** The connection type; Automatic goes by the address and falls back to SSE. */
private enum class TransportChoice(val label: Int, val kind: McpTransportKind?) {
    Automatic(R.string.connector_transport_auto, null),
    Http(R.string.connector_transport_http, McpTransportKind.STREAMABLE_HTTP),
    Sse(R.string.connector_transport_sse, McpTransportKind.SSE),
}

/** One header the user types for a key-signed server. */
private class HeaderEntry(name: String) {
    var name by mutableStateOf(name)
    var value by mutableStateOf("")
    var shown by mutableStateOf(false)
}

/**
 * Adds any MCP server: its name and address, how it signs in, and, under Advanced, a client the user
 * registered with the service, scopes and the connection type. Keys typed here connect it straight away;
 * a browser sign-in starts once it's added ([onAdded]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AddConnectionSheet(graph: AppGraph, onDismiss: () -> Unit, onAdded: (McpConnection) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var signIn by remember { mutableStateOf(SignInChoice.Automatic) }
    val headers = remember { mutableStateListOf(HeaderEntry(AUTHORIZATION)) }
    var advanced by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var secretShown by remember { mutableStateOf(false) }
    var scopes by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(TransportChoice.Automatic) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val offersClient = signIn == SignInChoice.Automatic || signIn == SignInChoice.Service

    fun add() {
        checking = true
        error = null
        scope.launch {
            val keys = if (signIn == SignInChoice.Key) headers.filter { it.name.isNotBlank() }.associate { it.name.trim() to it.value.trim() } else emptyMap()
            val added = graph.connectors.add(
                name = name,
                url = url,
                transport = transport.kind,
                headers = keys.keys.map { HeaderSpec(it, isRequired = true, isSecret = true) },
                auth = signIn.auth,
                scopes = if (offersClient) scopes.split(WHITESPACE).filter { it.isNotBlank() } else emptyList(),
                client = clientId.trim().takeIf { offersClient && it.isNotEmpty() }?.let { OAuthClientCredentials(it, clientSecret.trim().ifEmpty { null }) },
            )
            when (added) {
                is Outcome.Success -> if (keys.values.any { it.isNotEmpty() }) {
                    graph.connectors.connectWithHeaders(added.value, keys)
                    onDismiss()
                } else {
                    onAdded(added.value)
                }
                is Outcome.Failure -> error = added.error.message
            }
            checking = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!checking) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = WhittleSpace.xxl),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
                    Text(stringResource(R.string.connector_sheet_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.connector_sheet_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.connector_add_name)) },
                    singleLine = true,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text(stringResource(R.string.connector_add_url)) },
                    singleLine = true,
                    enabled = !checking,
                    isError = error != null,
                    supportingText = { Text(error ?: stringResource(R.string.connector_add_url_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionTitle(stringResource(R.string.connector_sign_in_label))
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                    SignInChoice.entries.forEachIndexed { index, choice ->
                        ChoiceRow(choice, selected = choice == signIn, index = index) {
                            haptics.perform(WhittleHaptic.Select)
                            signIn = choice
                        }
                    }
                }

                if (signIn == SignInChoice.Key) {
                    headers.forEachIndexed { index, header ->
                        HeaderCard(header, index, removable = index > 0, enabled = !checking) { headers.remove(header) }
                    }
                    TextButton(onClick = { headers += HeaderEntry("") }, enabled = !checking) {
                        WhittleIcon("add", size = WhittleSize.Icon.m)
                        Text(stringResource(R.string.connector_header_add), Modifier.padding(start = WhittleSpace.s))
                    }
                }

                AdvancedRow(
                    summary = stringResource(if (offersClient) R.string.connector_advanced_summary_client else R.string.connector_advanced_summary_transport),
                    open = advanced,
                ) {
                    haptics.perform(WhittleHaptic.Tap)
                    advanced = !advanced
                }
                AnimatedVisibility(advanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                        if (offersClient) {
                            Text(stringResource(R.string.connector_client_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            OutlinedTextField(
                                value = clientId,
                                onValueChange = { clientId = it },
                                label = { Text(stringResource(R.string.connector_client_id)) },
                                singleLine = true,
                                enabled = !checking,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SecretField(
                                value = clientSecret,
                                onValueChange = { clientSecret = it },
                                label = stringResource(R.string.connector_client_secret),
                                shown = secretShown,
                                onToggleShown = { secretShown = !secretShown },
                                enabled = !checking,
                            )
                            if (clientId.isNotBlank()) {
                                val address = graph.connectors.returnAddressFor(name)
                                ReturnAddress(address) {
                                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(address, address))) }
                                }
                            }
                            OutlinedTextField(
                                value = scopes,
                                onValueChange = { scopes = it },
                                label = { Text(stringResource(R.string.connector_scopes)) },
                                supportingText = { Text(stringResource(R.string.connector_scopes_hint)) },
                                singleLine = true,
                                enabled = !checking,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(stringResource(R.string.connector_transport_label), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                            TransportChoice.entries.forEachIndexed { index, choice ->
                                TransportButton(choice, selected = choice == transport, index = index, Modifier.weight(if (choice == TransportChoice.Http) WIDE_SEGMENT else 1f)) {
                                    haptics.perform(WhittleHaptic.Select)
                                    transport = choice
                                }
                            }
                        }
                        Text(stringResource(R.string.connector_transport_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }

            // Always in reach, however far the form scrolls.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = WhittleSpace.xxl, vertical = WhittleSpace.m),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    WhittleIcon("lock", size = WhittleSize.Icon.s, tint = colors.onSurfaceVariant)
                    Text(stringResource(R.string.connector_secrets_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (checking) CircularProgressIndicator(strokeWidth = WhittleSpace.hairline, modifier = Modifier.size(WhittleSize.Icon.xl))
                    TextButton(onClick = onDismiss, enabled = !checking) { Text(stringResource(R.string.connector_cancel)) }
                    Button(onClick = ::add, enabled = url.isNotBlank() && !checking, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.connector_add_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One way to sign in, as a row of a segmented group: radio, title and what it means. */
@Composable
private fun ChoiceRow(choice: SignInChoice, selected: Boolean, index: Int, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.onSecondaryContainer else colors.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(segmentedShape(index, SignInChoice.entries.size))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        WhittleIcon(
            if (selected) "radio_button_checked" else "radio_button_unchecked",
            size = WhittleSize.Icon.xl,
            tint = if (selected) colors.primary else colors.outline,
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(choice.title), style = MaterialTheme.typography.titleSmall, color = content)
            Text(
                stringResource(choice.body),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}

/** A header a key-signed server needs: its name and its value, which stays hidden unless shown. */
@Composable
private fun HeaderCard(header: HeaderEntry, index: Int, removable: Boolean, enabled: Boolean, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceContainer, RoundedCornerShape(WhittleRadius.l)).padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.connector_header_caption, index + 1), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (removable) {
                IconButton(onClick = onRemove, enabled = enabled) {
                    WhittleIcon("close", size = WhittleSize.Icon.l, contentDescription = stringResource(R.string.connector_header_remove))
                }
            }
        }
        OutlinedTextField(
            value = header.name,
            onValueChange = { header.name = it },
            label = { Text(stringResource(R.string.connector_header_name)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        val isAuthorization = header.name.trim().equals(AUTHORIZATION, ignoreCase = true)
        SecretField(
            value = header.value,
            onValueChange = { header.value = it },
            label = stringResource(if (isAuthorization) R.string.connector_header_key else R.string.connector_header_value),
            shown = header.shown,
            onToggleShown = { header.shown = !header.shown },
            enabled = enabled,
        )
        if (isAuthorization) {
            Text(stringResource(R.string.connector_header_bearer_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** A key or secret: masked unless the user shows it, and never in session recordings. */
@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, shown: Boolean, onToggleShown: () -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = onToggleShown) {
                WhittleIcon(
                    if (shown) "visibility_off" else "visibility",
                    size = WhittleSize.Icon.l,
                    contentDescription = stringResource(if (shown) R.string.connector_secret_hide else R.string.connector_secret_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().hiddenFromReplay(),
    )
}

/** Opens and closes the advanced settings; says what's inside while closed. */
@Composable
private fun AdvancedRow(summary: String, open: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) HALF_TURN else 0f, label = "advancedChevron")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(WhittleRadius.l)).clickable(role = Role.Button, onClick = onToggle).padding(vertical = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.connector_advanced), style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        WhittleIcon("expand_more", size = WhittleSize.Icon.xl, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(turn))
    }
}

/** Where this connection's sign-in returns, to register with the service, with a copy button. */
@Composable
private fun ReturnAddress(address: String, onCopy: () -> Unit) = CopyableText(
    text = address,
    copyDescription = stringResource(R.string.connector_return_copy),
    onCopy = onCopy,
    label = stringResource(R.string.connector_return_address),
    hint = stringResource(R.string.connector_return_hint),
)

/** One of the connected connection-type buttons. */
@Composable
private fun TransportButton(choice: TransportChoice, selected: Boolean, index: Int, modifier: Modifier, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(WhittleSize.Button.small)
            .clip(connectedShape(index, TransportChoice.entries.size))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHighest)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
            if (selected) WhittleIcon("check", size = WhittleSize.Icon.m, tint = colors.onSecondaryContainer)
            Text(
                stringResource(choice.label),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private const val AUTHORIZATION = "Authorization"
private const val HALF_TURN = 180f

/** The middle button's label is longer than the others. */
private const val WIDE_SEGMENT = 1.5f
private val WHITESPACE = Regex("\\s+")
