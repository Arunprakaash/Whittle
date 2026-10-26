package app.whittle.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import app.whittle.design.components.ComposerVoice
import app.whittle.design.tokens.WhittleHaptic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the chat screen's state comes from and its actions go: the phone's own chats, directly on the phone and over the
 * link on a computer. What only a device can do itself is its [ChatPlatform].
 */
interface ChatHost {
    val state: StateFlow<ChatScreenState>

    /** Moments the screen tells the user about, such as an attachment that wasn't added. */
    val events: Flow<ChatEvent>

    fun act(action: ChatAction)

    /** Makes a project; false when the name isn't one. */
    suspend fun createProject(name: String): Boolean

    /** The process card [key]'s latest output, at most [lines] of it. */
    suspend fun processOutput(key: String, lines: Int): String

    /** A workspace image at [path]: a thumbnail when [maxSidePx] is null, else at most that on its longer side. */
    suspend fun image(path: String, maxSidePx: Int?): ImageBitmap?
}

/** Something the screen tells the user. */
sealed interface ChatEvent {
    data class AttachmentRefused(val problem: AttachmentProblem) : ChatEvent

    /** Something happened in the chat on screen that the hand should feel: a step done, a card appearing. */
    data class Haptic(val haptic: WhittleHaptic) : ChatEvent
}

enum class AttachmentProblem { TooMany, TooLarge, Unreadable, NoWorkspace }

/** Where an attachment comes from. */
enum class AttachSource { Photos, Camera, Files }

/**
 * What the chat screen can only do through the device it runs on: the phone's mic and pickers, Android's permission
 * prompts and installer, and moving to the app's other screens. A computer offers what it can and leaves out the rest,
 * and its screen says those are on the phone.
 */
interface ChatPlatform {
    /** The composer's mic, or null where there's no speech recognizer. */
    @Composable
    fun rememberVoice(input: String, onInput: (String) -> Unit): ComposerVoice?

    /** Where attachments can come from here, each with what picks from it. */
    @Composable
    fun rememberAttachSources(): Map<AttachSource, () -> Unit>

    /** Allow on a capability card: asks the device, then answers the card. Null where capabilities can't be granted. */
    @Composable
    fun rememberCapabilityRequest(): ((ThreadRow.Capability) -> Unit)?

    /** The live preview of a process card's server, over the screen until [onDismiss]. */
    @Composable
    fun Preview(process: ThreadRow.Process, onDismiss: () -> Unit)

    /** Installing, opening and removing the apps Whittle builds; null where apps can't be installed. */
    val apps: AppInstalls?

    /** Shares a workspace file with another app; null where there's nothing to share with. */
    val share: ((path: String, name: String) -> Unit)?

    /** Says [message] briefly. */
    fun toast(message: String)

    /** Shown just above the composer, such as Linux finishing its setup. */
    @Composable
    fun AboveComposer()

    val navigation: ChatNavigation
}

/** The app's other screens, opened from the chat; each null where that screen isn't here yet. */
class ChatNavigation(
    val viewAllChats: (() -> Unit)? = null,
    val openSettings: (() -> Unit)? = null,
    val openProjects: (() -> Unit)? = null,
    /** Opens the file browser at a file, for the open chat. */
    val openFile: ((path: String, chat: String?) -> Unit)? = null,
    /** Opens the file browser at a folder (the project's, or the workspace's for ""), for the open chat. */
    val openFolder: ((path: String, chat: String?) -> Unit)? = null,
    /** Opens the model picker, from the note that the model can't see an attached photo. */
    val changeModel: (() -> Unit)? = null,
)

/** The phone's installer, for app cards. */
interface AppInstalls {
    /**
     * Starts installing the card's build: [needsAllow] when Android must first let Whittle install apps, [confirm] with what
     * the app could do that wasn't there before, which the user confirms before it goes on through [install].
     */
    fun begin(key: String, needsAllow: () -> Unit, confirm: (List<PowerRow>) -> Unit)

    fun install(key: String)

    /** Android's setting that lets Whittle install apps; false when it couldn't open. */
    fun allowInstalls(): Boolean

    /** Opens the installed app; false when it couldn't. */
    fun open(key: String): Boolean

    /** Asks Android to remove the app; false when it couldn't. */
    fun remove(key: String): Boolean

    /** Reads again whenever the user is back on the screen, as an install or removal may have finished elsewhere. */
    @Composable
    fun RefreshOnResume(key: String)
}

/** Loads workspace images: a thumbnail when [maxSidePx] is null, else at most that on the longer side. */
fun interface WorkspaceImageLoader {
    suspend fun load(path: String, maxSidePx: Int?): ImageBitmap?
}

/** The images in the chat, from its host. */
val LocalWorkspaceImages = staticCompositionLocalOf { WorkspaceImageLoader { _, _ -> null } }

/** Marks what the user and the agent wrote, did and made, which stays out of session recordings. */
val LocalUserContent = staticCompositionLocalOf<Modifier> { Modifier }

/** This, kept out of session recordings where the device makes them. */
@Composable
fun Modifier.userContent(): Modifier = then(LocalUserContent.current)
