package app.whittle.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.whittle.design.components.ReadingWidth
import app.whittle.design.components.SettingsChoiceRow
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.ThemeMode
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.window.BackHandler
import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.WindowTopBar
import app.whittle.files.ui.nowMillis
import app.whittle.files.ui.timeAgo
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Settings: the model, how Whittle looks and feels, what it shares, and the user's data. Changes apply at once. */
@Composable
fun SettingsScreen(host: SettingsHost, platform: SettingsPlatform, navigation: SettingsNavigation) {
    val state by host.state.collectAsState()
    val act = host::act
    BackHandler(enabled = true, onBack = navigation.back)
    var confirmingDelete by remember { mutableStateOf(false) }
    val chevron: @Composable () -> Unit = { WhittleIcon("chevron_right", size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            WindowTopBar {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(LocalWindowChrome.current?.insets ?: WindowInsets(0))
                        .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
                ) {
                    TopBarButton("arrow_back", stringResource(Res.string.settings_back), navigation.back)
                    Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.titleLarge)
                }
            }
        },
    ) { padding ->
        // On a wide window the page reads in a centred column; the whole width still scrolls.
        ReadingWidth(WhittleSpace.l, Modifier.padding(padding).fillMaxSize()) { inset ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = inset, vertical = WhittleSpace.l),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl),
            ) {
                item {
                    SettingsSection(stringResource(Res.string.settings_section_model)) {
                        SettingsRow(
                            icon = "smart_toy",
                            title = state.model ?: stringResource(Res.string.settings_model_none),
                            subtitle = null,
                            shape = segmentedShape(0, 1),
                            onClick = navigation.changeModel,
                            trailing = chevron.takeIf { navigation.changeModel != null },
                        )
                    }
                }
                item { platform.PhoneSections() }
                item {
                    // Three places of their own, each row named after the screen it opens and showing only its state.
                    SettingsSection(stringResource(Res.string.settings_section_workspace)) {
                        val image = state.linux.imageName
                        SettingsRow(
                            icon = "terminal",
                            title = stringResource(Res.string.linux_title),
                            subtitle = when (state.linux.kind) {
                                LinuxKind.NotSetUp -> stringResource(Res.string.linux_status_not_set_up, image)
                                LinuxKind.SettingUp -> stringResource(Res.string.linux_status_setting_up, image)
                                LinuxKind.Ready -> image
                                LinuxKind.Failed -> stringResource(Res.string.linux_status_failed, image)
                            },
                            shape = segmentedShape(0, 3),
                            onClick = navigation.linux,
                            trailing = chevron.takeIf { navigation.linux != null },
                        )
                        SettingsRow(
                            icon = "bookmarks",
                            title = stringResource(Res.string.memory_heading),
                            subtitle = memorySubtitle(state.memories, state.suggestedMemories),
                            shape = segmentedShape(1, 3),
                            onClick = navigation.memory,
                            trailing = chevron.takeIf { navigation.memory != null },
                        )
                        SettingsRow(
                            icon = "schedule",
                            title = stringResource(Res.string.automation_title),
                            subtitle = if (state.automationsOn > 0) {
                                stringResource(Res.string.automation_settings_on, state.automationsOn)
                            } else {
                                stringResource(Res.string.automation_settings_idle)
                            },
                            shape = segmentedShape(2, 3),
                            onClick = navigation.automations,
                            trailing = chevron.takeIf { navigation.automations != null },
                        )
                    }
                }
                item {
                    SettingsSection(stringResource(Res.string.settings_section_appearance)) {
                        SettingsChoiceRow(
                            icon = "contrast",
                            title = stringResource(Res.string.settings_theme_title),
                            shape = segmentedShape(0, 3),
                            enabled = state.loaded,
                        ) {
                            ThemeChoice(selected = state.themeMode, enabled = state.loaded, onSelect = { act(SettingsAction.SetTheme(it)) })
                        }
                        SettingsToggleRow(
                            icon = "wallpaper",
                            title = stringResource(Res.string.settings_wallpaper_title),
                            subtitle = null,
                            checked = state.wallpaperColors,
                            onCheckedChange = { act(SettingsAction.SetWallpaperColors(it)) },
                            shape = segmentedShape(1, 3),
                            enabled = state.wallpaperAvailable && state.loaded,
                        )
                        SettingsToggleRow(
                            icon = "vibration",
                            title = stringResource(Res.string.settings_haptics_title),
                            subtitle = null,
                            checked = state.haptics,
                            onCheckedChange = { act(SettingsAction.SetHaptics(it)) },
                            shape = segmentedShape(2, 3),
                            enabled = state.loaded,
                        )
                    }
                }
                item {
                    val assistant = platform.assistantHeld()
                    val rows = if (assistant != null) 2 else 1
                    SettingsSection(stringResource(Res.string.settings_section_tasks)) {
                        SettingsToggleRow(
                            icon = "notifications",
                            title = stringResource(Res.string.settings_background_title),
                            subtitle = null,
                            checked = state.keepRunningInBackground,
                            onCheckedChange = { on ->
                                act(SettingsAction.SetKeepRunning(on))
                                if (on) platform.requestNotifications?.invoke()
                            },
                            shape = segmentedShape(0, rows),
                            enabled = state.loaded,
                        )
                        if (assistant != null) {
                            SettingsRow(
                                icon = "assistant",
                                title = stringResource(Res.string.settings_assistant_title),
                                subtitle = stringResource(if (assistant) Res.string.settings_assistant_on else Res.string.settings_assistant_off),
                                shape = segmentedShape(1, rows),
                                onClick = platform::chooseAssistant,
                                trailing = chevron,
                            )
                        }
                    }
                }
                item {
                    val sharing = state.analyticsAvailable && state.shareUsageData
                    SettingsSection(stringResource(Res.string.settings_section_privacy)) {
                        SettingsToggleRow(
                            icon = "insights",
                            title = stringResource(Res.string.settings_usage_title),
                            subtitle = null,
                            checked = sharing,
                            onCheckedChange = { act(SettingsAction.SetShareUsage(it)) },
                            shape = segmentedShape(0, 2),
                            enabled = state.analyticsAvailable && state.loaded,
                        )
                        SettingsToggleRow(
                            icon = "screen_record",
                            title = stringResource(Res.string.settings_recordings_title),
                            subtitle = null,
                            checked = sharing && state.screenRecordings,
                            onCheckedChange = { act(SettingsAction.SetRecordings(it)) },
                            shape = segmentedShape(1, 2),
                            enabled = sharing,
                        )
                    }
                }
                item {
                    SettingsSection(stringResource(Res.string.settings_section_data)) {
                        SettingsRow(
                            icon = "delete",
                            title = stringResource(Res.string.settings_delete_all_title),
                            subtitle = null,
                            shape = segmentedShape(0, 1),
                            destructive = true,
                            onClick = { confirmingDelete = true },
                        )
                    }
                }
                item { About(state, platform, act) }
            }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(Res.string.settings_delete_all_confirm_title)) },
            text = { Text(stringResource(Res.string.settings_delete_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; act(SettingsAction.DeleteAllChats); navigation.back() }) {
                    Text(stringResource(Res.string.settings_delete_all_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(Res.string.settings_cancel)) } },
        )
    }
}

/** The version, a newer one when it's out (Update where it can install), and the daily check. */
@Composable
private fun About(state: SettingsState, platform: SettingsPlatform, act: (SettingsAction) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val update = state.update
    platform.UpdateEffects()
    SettingsSection(stringResource(Res.string.settings_section_about)) {
        SettingsRow(
            icon = "info",
            title = stringResource(Res.string.settings_version_title),
            subtitle = versionLine(state.version, update),
            shape = segmentedShape(0, 2),
            onClick = update.pageUrl?.let { url -> { uriHandler.openUri(url) } },
            trailing = if (update.latest == null) null else when (update.progress) {
                is UpdateProgress.Downloading, is UpdateProgress.Installing -> {
                    { CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline) }
                }
                else -> platform.installUpdate?.let { install ->
                    { TextButton(onClick = install) { Text(stringResource(Res.string.settings_update_button)) } }
                }
            },
        )
        SettingsToggleRow(
            icon = "download",
            title = stringResource(Res.string.settings_updates_title),
            subtitle = if (state.checkForUpdates) lastChecked(state.updatesAnsweredAt, state.updatesLastFailed) else null,
            checked = state.checkForUpdates,
            onCheckedChange = { act(SettingsAction.SetCheckForUpdates(it)) },
            shape = segmentedShape(1, 2),
            enabled = state.loaded,
        )
    }
}

/** The Version row's line: the version, then a newer one that's out, or how updating to it is going. */
@Composable
private fun versionLine(version: String, update: UpdateStatus): String = when (val progress = update.progress) {
    is UpdateProgress.Downloading -> stringResource(Res.string.settings_update_downloading, progress.version, progress.percent)
    is UpdateProgress.Installing -> stringResource(Res.string.settings_update_installing, progress.version)
    UpdateProgress.NeedsPermission -> stringResource(Res.string.settings_update_allow)
    is UpdateProgress.Failed -> stringResource(
        when (progress.reason) {
            UpdateFailureReason.Network -> Res.string.settings_update_failed_network
            UpdateFailureReason.Mismatch -> Res.string.settings_update_failed_mismatch
            UpdateFailureReason.NotWhittle -> Res.string.settings_update_failed_not_whittle
            UpdateFailureReason.Install -> Res.string.settings_update_failed_install
            UpdateFailureReason.NoApk -> Res.string.settings_update_failed_no_apk
        },
    )
    UpdateProgress.Idle -> update.latest?.let { stringResource(Res.string.settings_version_update, version, it) } ?: version
}

/**
 * When the update check last heard from GitHub, in plain words: "Checked 3 hours ago", "Checked yesterday". A check that
 * got no answer says so only while there's no earlier answer to show.
 */
/** The Memory row's state: how many things it remembers ("Empty" when none), and how many suggestions wait to be kept. */
@Composable
private fun memorySubtitle(memories: Int, suggested: Int): String {
    val remembered = if (memories == 0) stringResource(Res.string.memory_none) else pluralStringResource(Res.plurals.memory_count, memories, memories)
    if (suggested == 0) return remembered
    val waiting = pluralStringResource(Res.plurals.memory_suggested_count, suggested, suggested)
    return if (memories == 0) waiting else stringResource(Res.string.memory_count_and_suggested, remembered, waiting)
}

@Composable
private fun lastChecked(answeredAtMillis: Long?, lastFailed: Boolean): String {
    answeredAtMillis ?: return stringResource(if (lastFailed) Res.string.settings_updates_failed else Res.string.settings_updates_not_yet)
    val now = nowMillis()
    if (now - answeredAtMillis < MILLIS_PER_MINUTE) return stringResource(Res.string.settings_updates_checked_now)
    // Android writes "Yesterday" capitalised, for a line on its own; here it follows "Checked".
    return stringResource(Res.string.settings_updates_checked, timeAgo(answeredAtMillis, now).replaceFirstChar { it.lowercase() })
}

/** System, Light or Dark as one connected button group (M3 Expressive); the choice applies at once. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeChoice(selected: ThemeMode, enabled: Boolean, onSelect: (ThemeMode) -> Unit) {
    val haptics = WhittleTheme.haptics
    val choices = listOf(
        Triple(ThemeMode.System, "brightness_auto", stringResource(Res.string.settings_theme_system)),
        Triple(ThemeMode.Light, "light_mode", stringResource(Res.string.settings_theme_light)),
        Triple(ThemeMode.Dark, "dark_mode", stringResource(Res.string.settings_theme_dark)),
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

private const val MILLIS_PER_MINUTE = 60_000L
