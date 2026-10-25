package app.whittle.settings

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.collectAsState
import app.whittle.connectors.mcp.McpConnection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonGroupDefaults
import app.whittle.design.components.SettingsChoiceRow
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.theme.ThemeMode
import app.whittle.background.rememberNotificationPermissionRequest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.BuildConfig
import app.whittle.linux.LinuxState
import app.whittle.R
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.llm.api.ModelDescriptor
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Intent

/** Settings: the model, how Whittle looks and feels, what it shares, and the user's data. Changes apply at once. */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    settings: UserSettings?,
    model: ModelDescriptor?,
    onBack: () -> Unit,
    onChangeModel: () -> Unit,
    onDeleteAllChats: () -> Unit,
    onBrowseConnections: () -> Unit,
    onConnectionTools: (McpConnection) -> Unit,
    onMemory: () -> Unit,
    onAutomations: () -> Unit,
    onLinux: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmingDelete by remember { mutableStateOf(false) }
    val requestNotifications = rememberNotificationPermissionRequest()
    // Writes go to the app's scope so leaving the screen never drops a change.
    val save = { change: suspend SettingsStore.() -> Unit -> graph.appScope.launch { graph.settings.change() }; Unit }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("arrow_back", stringResource(R.string.settings_back), onBack)
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl),
        ) {
            item {
                SettingsSection(stringResource(R.string.settings_section_model)) {
                    SettingsRow(
                        icon = "smart_toy",
                        title = model?.displayName ?: stringResource(R.string.settings_model_none),
                        subtitle = null,
                        shape = segmentedShape(0, 1),
                        onClick = onChangeModel,
                        trailing = { WhittleIcon("chevron_right", size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
            item { ConnectionsSection(graph, onBrowse = onBrowseConnections, onChooseTools = onConnectionTools) }
            item { PhoneSection(graph, settings) }
            item {
                val linux by graph.linux.state.collectAsState()
                val imageName = graph.linux.image?.name.orEmpty()
                SettingsSection(stringResource(R.string.linux_title)) {
                    SettingsRow(
                        icon = "terminal",
                        title = stringResource(R.string.linux_settings_row),
                        subtitle = when (linux) {
                            LinuxState.NotSetUp -> stringResource(R.string.linux_status_not_set_up, imageName)
                            is LinuxState.SettingUp -> stringResource(R.string.linux_status_setting_up, imageName)
                            is LinuxState.Ready -> imageName
                            is LinuxState.Failed -> stringResource(R.string.linux_status_failed, imageName)
                        },
                        shape = segmentedShape(0, 1),
                        onClick = onLinux,
                        trailing = { WhittleIcon("chevron_right", size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
            item {
                val remembered by graph.memory.all.collectAsState()
                SettingsSection(stringResource(R.string.settings_section_memory)) {
                    SettingsRow(
                        icon = "bookmarks",
                        title = stringResource(R.string.memory_title),
                        subtitle = pluralStringResource(R.plurals.memory_count, remembered.size, remembered.size),
                        shape = segmentedShape(0, 1),
                        onClick = onMemory,
                        trailing = { WhittleIcon("chevron_right", size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
            item {
                val enabledCount = graph.automations.all.collectAsStateWithLifecycle().value.count { it.enabled }
                SettingsSection(stringResource(R.string.settings_section_automations)) {
                    SettingsRow(
                        icon = "schedule",
                        title = stringResource(R.string.automation_title),
                        subtitle = if (enabledCount > 0) stringResource(R.string.automation_settings_on, enabledCount) else stringResource(R.string.automation_settings_idle),
                        shape = segmentedShape(0, 1),
                        onClick = onAutomations,
                        trailing = { WhittleIcon("chevron_right", size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
            item {
                val wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                SettingsSection(stringResource(R.string.settings_section_appearance)) {
                    SettingsChoiceRow(
                        icon = "contrast",
                        title = stringResource(R.string.settings_theme_title),
                        shape = segmentedShape(0, 3),
                        enabled = settings != null,
                    ) {
                        ThemeChoice(
                            selected = settings?.themeMode ?: graph.defaultAppearance.themeMode,
                            enabled = settings != null,
                            onSelect = { mode -> save { setThemeMode(mode) } },
                        )
                    }
                    SettingsToggleRow(
                        icon = "wallpaper",
                        title = stringResource(R.string.settings_wallpaper_title),
                        subtitle = null,
                        checked = settings?.wallpaperColors == true,
                        onCheckedChange = { on -> save { setWallpaperColors(on) } },
                        shape = segmentedShape(1, 3),
                        enabled = wallpaperAvailable && settings != null,
                    )
                    SettingsToggleRow(
                        icon = "vibration",
                        title = stringResource(R.string.settings_haptics_title),
                        subtitle = null,
                        checked = settings?.haptics == true,
                        onCheckedChange = { on -> save { setHaptics(on) } },
                        shape = segmentedShape(2, 3),
                        enabled = settings != null,
                    )
                }
            }
            item {
                SettingsSection(stringResource(R.string.settings_section_tasks)) {
                    SettingsToggleRow(
                        icon = "notifications",
                        title = stringResource(R.string.settings_background_title),
                        subtitle = null,
                        checked = settings?.keepRunningInBackground == true,
                        onCheckedChange = { on ->
                            save { setKeepRunningInBackground(on) }
                            if (on) requestNotifications()
                        },
                        shape = segmentedShape(0, 1),
                        enabled = settings != null,
                    )
                }
            }
            item {
                val sharing = graph.analyticsAvailable && settings?.shareUsageData == true
                SettingsSection(stringResource(R.string.settings_section_privacy)) {
                    SettingsToggleRow(
                        icon = "insights",
                        title = stringResource(R.string.settings_usage_title),
                        subtitle = null,
                        checked = sharing,
                        onCheckedChange = { on -> save { setShareUsageData(on) } },
                        shape = segmentedShape(0, 2),
                        enabled = graph.analyticsAvailable && settings != null,
                    )
                    SettingsToggleRow(
                        icon = "screen_record",
                        title = stringResource(R.string.settings_recordings_title),
                        subtitle = null,
                        checked = sharing && settings?.screenRecordings == true,
                        onCheckedChange = { on -> save { setScreenRecordings(on) } },
                        shape = segmentedShape(1, 2),
                        enabled = sharing,
                    )
                }
            }
            item {
                SettingsSection(stringResource(R.string.settings_section_data)) {
                    SettingsRow(
                        icon = "delete",
                        title = stringResource(R.string.settings_delete_all_title),
                        subtitle = null,
                        shape = segmentedShape(0, 1),
                        destructive = true,
                        onClick = { confirmingDelete = true },
                    )
                }
            }
            item {
                val update by graph.updates.available.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                SettingsSection(stringResource(R.string.settings_section_about)) {
                    SettingsRow(
                        icon = "info",
                        title = stringResource(R.string.settings_version_title),
                        subtitle = update?.let { stringResource(R.string.settings_version_update, version, it.version) } ?: version,
                        shape = segmentedShape(0, 2),
                        onClick = update?.let { release ->
                            {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, release.pageUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } catch (_: ActivityNotFoundException) {
                                    // No browser to show the release in.
                                }
                            }
                        },
                    )
                    SettingsToggleRow(
                        icon = "download",
                        title = stringResource(R.string.settings_updates_title),
                        subtitle = stringResource(R.string.settings_updates_subtitle),
                        checked = settings?.checkForUpdates == true,
                        onCheckedChange = { on ->
                            save { setCheckForUpdates(on) }
                            if (on) graph.appScope.launch { graph.updates.checkIfDue() }
                        },
                        shape = segmentedShape(1, 2),
                        enabled = settings != null,
                    )
                }
            }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.settings_delete_all_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDeleteAllChats(); onBack() }) {
                    Text(stringResource(R.string.settings_delete_all_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}


/** System, Light or Dark as one connected button group (M3 Expressive); the choice applies at once. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeChoice(selected: ThemeMode, enabled: Boolean, onSelect: (ThemeMode) -> Unit) {
    val haptics = WhittleTheme.haptics
    val choices = listOf(
        Triple(ThemeMode.System, "brightness_auto", stringResource(R.string.settings_theme_system)),
        Triple(ThemeMode.Light, "light_mode", stringResource(R.string.settings_theme_light)),
        Triple(ThemeMode.Dark, "dark_mode", stringResource(R.string.settings_theme_dark)),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        choices.forEachIndexed { index, (mode, icon, label) ->
            val checked = mode == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { haptics.perform(WhittleHaptic.Select); onSelect(mode) },
                enabled = enabled,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                WhittleIcon(icon, fill = checked, size = WhittleSize.Icon.m)
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
