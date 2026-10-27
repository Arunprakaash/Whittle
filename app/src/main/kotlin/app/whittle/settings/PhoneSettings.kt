package app.whittle.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.whittle.AppGraph
import app.whittle.BuildConfig
import app.whittle.assistant.AssistantRole
import app.whittle.background.rememberNotificationPermissionRequest
import app.whittle.chat.ChatViewModel
import android.widget.Toast
import app.whittle.connections.ui.ConnectionsHost
import app.whittle.connections.ui.ConnectionsPlatform
import app.whittle.connections.ui.ConnectionsSection
import app.whittle.design.tokens.WhittleSpace
import app.whittle.linux.kindOf
import app.whittle.settings.ui.LinuxStatus
import app.whittle.settings.ui.SettingsAction
import app.whittle.settings.ui.SettingsHost
import app.whittle.settings.ui.SettingsPlatform
import app.whittle.settings.ui.SettingsState
import app.whittle.settings.ui.UpdateFailureReason
import app.whittle.settings.ui.UpdateProgress
import app.whittle.settings.ui.UpdateStatus
import app.whittle.updates.UpdateFailure
import app.whittle.updates.UpdateState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The phone's settings as the Settings page shows them, and its changes saved: for the phone and the user's computers alike. */
class PhoneSettingsHost(private val graph: AppGraph, private val chat: ChatViewModel) : SettingsHost {
    override val state: StateFlow<SettingsState> = combine(
        combine(graph.currentSettings, graph.connections.model, ::Pair),
        combine(graph.linux.state, combine(graph.memory.all, graph.memorySuggestions.pending, ::Pair), graph.automations.all, ::Triple),
        combine(graph.updates.available, graph.updates.answeredAt, graph.updates.lastFailed, ::Triple),
        graph.selfUpdate.state,
    ) { (settings, model), (linux, memoryLists, automations), (release, answeredAt, lastFailed), updating ->
        SettingsState(
            loaded = settings != null,
            model = model?.displayName,
            linux = LinuxStatus(
                kind = kindOf(linux),
                imageName = graph.linux.image?.name.orEmpty(),
            ),
            memories = memoryLists.first.size,
            suggestedMemories = memoryLists.second.size,
            automationsOn = automations.count { it.enabled },
            themeMode = settings?.themeMode ?: graph.defaultAppearance.themeMode,
            wallpaperColors = settings?.wallpaperColors == true,
            wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            haptics = settings?.haptics == true,
            keepRunningInBackground = settings?.keepRunningInBackground == true,
            analyticsAvailable = graph.analyticsAvailable,
            shareUsageData = settings?.shareUsageData == true,
            screenRecordings = settings?.screenRecordings == true,
            version = BuildConfig.VERSION_NAME,
            update = UpdateStatus(latest = release?.version, pageUrl = release?.pageUrl, progress = progress(updating)),
            checkForUpdates = settings?.checkForUpdates == true,
            updatesAnsweredAt = answeredAt,
            updatesLastFailed = lastFailed,
        )
    }.stateIn(graph.appScope, SharingStarted.Eagerly, SettingsState())

    // Writes go to the app's scope so leaving the screen never drops a change.
    override fun act(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetTheme -> save { setThemeMode(action.mode) }
            is SettingsAction.SetWallpaperColors -> save { setWallpaperColors(action.on) }
            is SettingsAction.SetHaptics -> save { setHaptics(action.on) }
            is SettingsAction.SetKeepRunning -> save { setKeepRunningInBackground(action.on) }
            is SettingsAction.SetShareUsage -> save { setShareUsageData(action.on) }
            is SettingsAction.SetRecordings -> save { setScreenRecordings(action.on) }
            is SettingsAction.SetCheckForUpdates -> {
                save { setCheckForUpdates(action.on) }
                // Switched on, it checks now: the user expects to see it work, and it's one request.
                if (action.on) graph.appScope.launch { graph.updates.checkIfDue(force = true) }
            }
            SettingsAction.DeleteAllChats -> chat.deleteAllChats()
        }
    }

    private fun save(change: suspend SettingsStore.() -> Unit) {
        graph.appScope.launch { graph.settings.change() }
    }

    private fun progress(state: UpdateState): UpdateProgress = when (state) {
        UpdateState.Idle -> UpdateProgress.Idle
        is UpdateState.NeedsPermission -> UpdateProgress.NeedsPermission
        is UpdateState.Downloading -> UpdateProgress.Downloading(state.version, state.percent)
        is UpdateState.Installing -> UpdateProgress.Installing(state.version)
        is UpdateState.Failed -> UpdateProgress.Failed(
            when (state.reason) {
                UpdateFailure.NETWORK -> UpdateFailureReason.Network
                UpdateFailure.MISMATCH -> UpdateFailureReason.Mismatch
                UpdateFailure.NOT_WHITTLE -> UpdateFailureReason.NotWhittle
                UpdateFailure.INSTALL -> UpdateFailureReason.Install
                UpdateFailure.NO_APK -> UpdateFailureReason.NoApk
            },
        )
    }
}

/** What Settings do through the phone itself: its connections and features, Android's prompts and roles, and updates. */
@Composable
fun rememberPhoneSettingsPlatform(
    graph: AppGraph,
    connections: ConnectionsHost,
    onBrowseConnections: () -> Unit,
    onConnectionTools: (connectionId: String) -> Unit,
): SettingsPlatform {
    val context = LocalContext.current
    val requestNotifications = rememberNotificationPermissionRequest()
    val connectionsPlatform = rememberPhoneConnectionsPlatform()
    return remember(graph, context, requestNotifications, connectionsPlatform) {
        object : SettingsPlatform {
            @Composable
            override fun PhoneSections() {
                val settings by graph.currentSettings.collectAsState()
                // Two sections, spaced as the page spaces its own.
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl)) {
                    ConnectionsSection(connections, connectionsPlatform, onBrowse = onBrowseConnections, onChooseTools = onConnectionTools)
                    PhoneSection(graph, settings)
                }
            }

            override val requestNotifications: () -> Unit = requestNotifications

            @Composable
            override fun assistantHeld(): Boolean {
                // Read again when the user comes back from Android's settings.
                var held by remember { mutableStateOf(AssistantRole.held(context)) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { held = AssistantRole.held(context) }
                return held
            }

            override fun chooseAssistant() = AssistantRole.choose(context)

            override val installUpdate: () -> Unit = graph.selfUpdate::start

            @Composable
            override fun UpdateEffects() {
                val updating by graph.selfUpdate.state.collectAsState()
                // Android's install setting, the first time: the update waits there and carries on when the user is back.
                LaunchedEffect(updating) {
                    if (updating is UpdateState.NeedsPermission) openInstallSetting(context)
                }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    if (graph.selfUpdate.state.value is UpdateState.NeedsPermission) graph.selfUpdate.start()
                }
            }
        }
    }
}

/** What Connections do through the phone: a service's sign-in page opens here, and messages are toasts. */
@Composable
fun rememberPhoneConnectionsPlatform(): ConnectionsPlatform {
    val context = LocalContext.current
    return remember(context) { ConnectionsPlatform(signsInHere = true, toast = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }) }
}

/** Android's "Install unknown apps" page for Whittle. */
private fun openInstallSetting(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No such page on this phone; the row still says to allow installs.
    }
}
