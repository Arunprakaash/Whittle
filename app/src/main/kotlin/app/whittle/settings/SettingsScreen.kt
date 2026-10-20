package app.whittle.settings

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
import app.whittle.AppGraph
import app.whittle.BuildConfig
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

/** Settings: the model, how Whittle looks and feels, what it shares, and the user's data. Changes apply at once. */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    settings: UserSettings?,
    model: ModelDescriptor?,
    onBack: () -> Unit,
    onChangeModel: () -> Unit,
    onDeleteAllChats: () -> Unit,
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
                        subtitle = stringResource(R.string.settings_model_body),
                        shape = segmentedShape(0, 1),
                        onClick = onChangeModel,
                        trailing = { WhittleIcon("chevron_right", size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
            item {
                val wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                SettingsSection(stringResource(R.string.settings_section_appearance)) {
                    SettingsToggleRow(
                        icon = "wallpaper",
                        title = stringResource(R.string.settings_wallpaper_title),
                        subtitle = stringResource(if (wallpaperAvailable) R.string.settings_wallpaper_body else R.string.settings_wallpaper_unavailable),
                        checked = settings?.wallpaperColors == true,
                        onCheckedChange = { on -> save { setWallpaperColors(on) } },
                        shape = segmentedShape(0, 2),
                        enabled = wallpaperAvailable && settings != null,
                    )
                    SettingsToggleRow(
                        icon = "vibration",
                        title = stringResource(R.string.settings_haptics_title),
                        subtitle = stringResource(R.string.settings_haptics_body),
                        checked = settings?.haptics == true,
                        onCheckedChange = { on -> save { setHaptics(on) } },
                        shape = segmentedShape(1, 2),
                        enabled = settings != null,
                    )
                }
            }
            item {
                SettingsSection(stringResource(R.string.settings_section_tasks)) {
                    SettingsToggleRow(
                        icon = "notifications",
                        title = stringResource(R.string.settings_background_title),
                        subtitle = stringResource(R.string.settings_background_body),
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
                        subtitle = stringResource(if (graph.analyticsAvailable) R.string.settings_usage_body else R.string.settings_usage_unavailable),
                        checked = sharing,
                        onCheckedChange = { on -> save { setShareUsageData(on) } },
                        shape = segmentedShape(0, 2),
                        enabled = graph.analyticsAvailable && settings != null,
                    )
                    SettingsToggleRow(
                        icon = "screen_record",
                        title = stringResource(R.string.settings_recordings_title),
                        subtitle = stringResource(
                            when {
                                !sharing -> R.string.settings_recordings_needs_usage
                                graph.recordingsShowContent -> R.string.settings_recordings_show_content
                                else -> R.string.settings_recordings_body
                            },
                        ),
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
                        subtitle = stringResource(R.string.settings_delete_all_body),
                        shape = segmentedShape(0, 1),
                        destructive = true,
                        onClick = { confirmingDelete = true },
                    )
                }
            }
            item {
                SettingsSection(stringResource(R.string.settings_section_about)) {
                    SettingsRow(
                        icon = "info",
                        title = stringResource(R.string.settings_version_title),
                        subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        shape = segmentedShape(0, 1),
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
