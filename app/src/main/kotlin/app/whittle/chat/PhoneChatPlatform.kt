package app.whittle.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.apps.APP_BUILDING
import app.whittle.apps.ToolchainFailure
import app.whittle.apps.shortReason
import app.whittle.chat.ui.AppInstalls
import app.whittle.chat.ui.AttachSource
import app.whittle.chat.ui.ChatNavigation
import app.whittle.chat.ui.ChatPlatform
import app.whittle.chat.ui.PowerRow
import app.whittle.chat.ui.ThreadRow
import app.whittle.design.components.ComposerVoice
import app.whittle.files.shareFile
import app.whittle.linux.LinuxProgressStrip
import app.whittle.phone.rememberCapabilityRequest
import app.whittle.settings.VoiceDefaults
import app.whittle.tools.android.AndroidCapabilities

/** What the chat screen does through the phone itself: its mic, pickers, permission prompts, installer and previews. */
@Composable
fun rememberPhoneChatPlatform(chat: ChatViewModel, graph: AppGraph, navigation: ChatNavigation): ChatPlatform {
    val context = LocalContext.current
    return remember(chat, graph, navigation, context) { PhoneChatPlatform(chat, graph, navigation, context) }
}

private class PhoneChatPlatform(
    private val chat: ChatViewModel,
    private val graph: AppGraph,
    override val navigation: ChatNavigation,
    private val context: Context,
) : ChatPlatform {
    @Composable
    override fun rememberVoice(input: String, onInput: (String) -> Unit): ComposerVoice? = rememberVoice(graph.voiceDefaults, input, onInput)

    @Composable
    override fun rememberAttachSources(): Map<AttachSource, () -> Unit> {
        val pickers = rememberAttachmentPickers(chat)
        return remember(pickers) { mapOf(AttachSource.Photos to pickers.photos, AttachSource.Camera to pickers.camera, AttachSource.Files to pickers.files) }
    }

    @Composable
    override fun rememberCapabilityRequest(): (ThreadRow.Capability) -> Unit = rememberCapabilityRequest(chat)

    @Composable
    override fun Preview(process: ThreadRow.Process, onDismiss: () -> Unit) {
        val item = chat.state.value.items.firstOrNull { it.key == process.key } as? ThreadItem.Process ?: return
        LivePreviewSheet(item, chat, graph.previewSheetHeight, onDismiss)
    }

    override val apps: AppInstalls = object : AppInstalls {
        override fun begin(key: String, needsAllow: () -> Unit, confirm: (List<PowerRow>) -> Unit) {
            val card = card(key) ?: return
            chat.beginInstall(card, needsAllow, confirm = { powerful -> confirm(powerful.map { PowerRow(it.name, it.kind.name, it.sentence) }) })
        }

        override fun install(key: String) {
            card(key)?.let(chat::installApp)
        }

        override fun allowInstalls(): Boolean = start(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))

        override fun open(key: String): Boolean {
            val card = card(key) ?: return false
            val launch = context.packageManager.getLaunchIntentForPackage(card.packageName) ?: return false
            return start(launch)
        }

        override fun remove(key: String): Boolean {
            val card = card(key) ?: return false
            return start(Intent(Intent.ACTION_DELETE, Uri.parse("package:${card.packageName}")))
        }

        @Composable
        override fun RefreshOnResume(key: String) {
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { card(key)?.let(chat::refreshApp) }
        }

        private fun card(key: String): ThreadItem.App? = chat.state.value.items.firstOrNull { it.key == key } as? ThreadItem.App

        private fun start(intent: Intent): Boolean = try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    override val share: (String, String) -> Unit = { path, name -> shareFile(context, path, name) }

    override fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    @Composable
    override fun AboveComposer() = LinuxProgressStrip(graph, compact = true)
}

/**
 * The composer's mic: tap to speak, and the words land in the input as they're heard, after anything already
 * typed. Null when the phone has no speech recognizer.
 */
@Composable
private fun rememberVoice(defaults: VoiceDefaults, input: String, onInput: (String) -> Unit): ComposerVoice? {
    val context = LocalContext.current
    val voice = remember(context, defaults) { VoiceInput(context, defaults) }
    if (!remember(voice) { voice.available }) return null
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    val currentInput by rememberUpdatedState(input)
    val currentOnInput by rememberUpdatedState(onInput)
    val errors = mapOf(
        VoiceError.NoPermission to stringResource(R.string.voice_no_permission),
        VoiceError.Unavailable to stringResource(R.string.voice_unavailable),
        VoiceError.Network to stringResource(R.string.voice_network),
        VoiceError.Other to stringResource(R.string.voice_failed),
    )
    val quiet = {
        voice.cancel()
        listening = false
        level = 0f
    }
    // Listening belongs to the screen in front of the user; leaving the app ends it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (listening) quiet() }
    val start = {
        // What was typed before speaking stays; each partial result replaces only what's been heard so far.
        val base = currentInput.trimEnd().let { if (it.isEmpty()) it else "$it " }
        listening = true
        voice.start(object : VoiceListener {
            override fun onPartial(text: String) = currentOnInput(base + text)

            override fun onFinal(text: String) = currentOnInput(base + text)

            override fun onLevel(heard: Float) {
                level = heard
            }

            override fun onEnd(error: VoiceError?) {
                quiet()
                error?.let { Toast.makeText(context, errors.getValue(it), Toast.LENGTH_LONG).show() }
            }
        })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else Toast.makeText(context, errors.getValue(VoiceError.NoPermission), Toast.LENGTH_LONG).show()
    }
    return ComposerVoice(
        listening = listening,
        level = level,
        listeningPlaceholder = stringResource(R.string.composer_listening),
        startLabel = stringResource(R.string.composer_speak),
        stopLabel = stringResource(R.string.composer_stop_listening),
        onToggle = {
            when {
                listening -> voice.stop()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> start()
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onTyped = { if (listening) quiet() },
    )
}

/**
 * Allow on a capability card shows Android's own permission prompt (Health Connect's screen for health data), then
 * reports the result. If access was already granted (say, in system settings), it reports straight away. App building
 * has no prompt: Allow sets it up.
 */
@Composable
private fun rememberCapabilityRequest(chat: ChatViewModel): (ThreadRow.Capability) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    var requestingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val answer = { granted: Boolean ->
        (chat.state.value.items.firstOrNull { it.key == requestingKey } as? ThreadItem.Capability)?.let { chat.answer(it, granted) }
        requestingKey = null
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> answer(granted) }
    // Health data is granted on Health Connect's own screen (or needs Health Connect from Play first).
    val healthRequest = rememberCapabilityRequest(chat.healthConnect) { granted -> answer(granted) }
    return { row ->
        val item = chat.state.value.items.firstOrNull { it.key == row.key } as? ThreadItem.Capability
        if (item != null) {
            val permission = AndroidCapabilities.permissionFor(item.capability)
            when {
                item.capability == APP_BUILDING -> chat.setUpAppBuilding(
                    item,
                    needsLinux = resources.getString(R.string.app_building_needs_linux),
                    failed = { kind, detail ->
                        val why = if (kind == ToolchainFailure.Output && detail != null) shortReason(detail) else resources.getString(setupFailureWords(kind))
                        resources.getString(R.string.app_building_setup_failed, why)
                    },
                )
                item.capability in AndroidCapabilities.HEALTH_CAPABILITIES -> {
                    requestingKey = item.key
                    healthRequest(listOf(item.capability))
                }
                permission == null || AndroidCapabilities.isGranted(context, item.capability) -> chat.answer(item, granted = true)
                else -> {
                    requestingKey = item.key
                    launcher.launch(permission)
                }
            }
        }
    }
}

/** The card's words for why app building's setup failed. */
private fun setupFailureWords(kind: ToolchainFailure): Int = when (kind) {
    ToolchainFailure.TimedOut -> R.string.app_setup_timed_out
    ToolchainFailure.Interrupted -> R.string.app_setup_interrupted
    ToolchainFailure.NotConfirmed -> R.string.app_setup_not_confirmed
    ToolchainFailure.NotStarted -> R.string.app_setup_not_started
    ToolchainFailure.InUse -> R.string.app_setup_in_use
    ToolchainFailure.Output -> R.string.app_setup_output
}
