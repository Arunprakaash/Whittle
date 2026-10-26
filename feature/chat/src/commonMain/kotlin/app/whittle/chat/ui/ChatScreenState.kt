package app.whittle.chat.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the chat screen draws, as plain values: the phone makes it from its chats and tasks, and a computer receives
 * the same over its link, so both draw one screen. Words that come from the agent, tools and config are already in it;
 * the screen's own words are its string resources.
 */
@Serializable
data class ChatScreenState(
    /** True until the last chat has been loaded, so the empty state doesn't flash at launch. */
    val restoring: Boolean = true,
    /** Saved chats, newest first. */
    val chats: List<ChatEntry> = emptyList(),
    val currentChat: String? = null,
    /** Other chats with a task working now, which keep going while this one is open. */
    val workingElsewhere: Set<String> = emptySet(),
    val items: List<ThreadRow> = emptyList(),
    val status: RunStatus? = null,
    val input: String = "",
    /** The model's name; null before one is connected, when nothing can be sent. */
    val model: String? = null,
    /** An attached photo the current model would only see as a file. */
    val imagesUnseen: Boolean = false,
    /** The project this chat works on; null for the whole workspace. */
    val project: String? = null,
    /** The workspace's projects, most recently changed first. */
    val projects: List<ProjectEntry> = emptyList(),
    /** Photos and files added to the next message, already saved in the workspace. */
    val attachments: List<AttachmentEntry> = emptyList(),
    /** How many picked items are still being saved. */
    val attaching: Int = 0,
    /** The screen the assistant read, going with the next message. */
    val screen: ScreenEntry? = null,
    /** What the composer does as soon as it's on screen: asked for by a quick start or an app idea. */
    val composerStart: ComposerStart? = null,
    /** The process card whose preview opens now, asked for from outside the chat. */
    val openPreview: String? = null,
    /** The first chat's things to try. */
    val starters: List<Suggestion> = emptyList(),
    /** A new chat's things to build. */
    val appIdeas: List<Suggestion> = emptyList(),
    /** Whether Ask to fix can send now. */
    val askToFixReady: Boolean = false,
    /** The capability card whose app-building setup is under way. */
    val appBuildingSetup: String? = null,
    /** Draws the user's avatar; null until it's made. */
    val avatarSeed: Long? = null,
    /** Where attachments are saved, as the attach sheet says. */
    val uploadsPath: String = "",
    val limits: ChatLimits = ChatLimits(),
    /** How the phone looks, for a computer to look the same. */
    val look: PhoneLook = PhoneLook(),
) {
    val running: Boolean get() = status != null
}

/** The numbers the screen lays itself out and words its limits by, from the phone's config. */
@Serializable
data class ChatLimits(
    val sidebarMaxChats: Int = 0,
    val recentProjects: Int = 0,
    val maxAttachments: Int = 0,
    val maxAttachmentBytes: Long = 0,
    /** A photo's longer side in the full-screen viewer. */
    val viewerImageSidePx: Int = 0,
    /** Share of the screen a live preview's sheet takes. */
    val previewHeight: Float = 1f,
)

/** What the composer does as soon as it's on screen. */
@Serializable
enum class ComposerStart { Type, Listen }

/** A saved chat in the list. A null [title] shows as an untitled chat. */
@Serializable
data class ChatEntry(val id: String, val title: String?, val updatedAtMillis: Long, val pinned: Boolean)

@Serializable
data class ProjectEntry(val name: String, val changedAtMillis: Long)

/** A photo or file in the workspace at [path]; [icon] is the file browser's for its kind. */
@Serializable
data class AttachmentEntry(val path: String, val name: String, val sizeBytes: Long, val isImage: Boolean, val icon: String)

/** The screen the assistant read; [appName] is null when the app is unknown. */
@Serializable
data class ScreenEntry(val appName: String?)

/** A starter or an app idea: its icon, title and the words it sends or fills in. */
@Serializable
data class Suggestion(val icon: String, val title: String, val prompt: String)

/** The pinned status line while a task works. A null [verb] means the agent is between actions. */
@Serializable
data class RunStatus(
    val state: RunState,
    val verb: String?,
    val startedAtMillis: Long,
    /** The agent's plan for this run, when it wrote one. */
    val plan: List<PlanRow>? = null,
    /** The provider failed for a passing reason and the call is being tried again. */
    val retrying: Boolean = false,
)

@Serializable
enum class RunState { Thinking, Acting, Done }

@Serializable
data class PlanRow(val text: String, val state: PlanRowState)

@Serializable
enum class PlanRowState { Pending, Active, Done }

/** One tool call as the user sees it; [file] is what it wrote, relative to the workspace, which it opens. */
@Serializable
data class StepRow(
    val id: String,
    val icon: String,
    val label: String,
    val status: StepRowStatus,
    val inspection: String? = null,
    val site: String? = null,
    val file: String? = null,
)

@Serializable
enum class StepRowStatus { Running, Done, Failed, Blocked, NeedsAccess, Stopped }

/** A card's words: what will happen, and its buttons. The payload is the exact thing that will happen. */
@Serializable
data class CardText(
    val icon: String,
    val title: String,
    val body: String,
    val payloadLabel: String? = null,
    val payload: String? = null,
    val approve: String,
    val decline: String,
    /** The service the action belongs to; its icon stands in for [icon] once loaded. */
    val site: String? = null,
)

@Serializable
data class SourceRow(val url: String, val title: String, val host: String)

/** A process the agent started, as it runs now; null on the card when it isn't running any more (say, after a restart of the app). */
@Serializable
data class ProcessRun(
    val starting: Boolean,
    val running: Boolean,
    val exited: Boolean,
    val exitCode: Int?,
    val port: Int?,
    /** Something answers on [port]. */
    val listening: Boolean,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
) {
    val live: Boolean get() = starting || running
}

/** What an app's powerful permission lets it do, in plain words. */
@Serializable
data class PowerRow(val name: String, val kind: String, val sentence: String)

@Serializable
enum class InstallOutcome { Installed, Cancelled, KeyChanged, Blocked, Failed, Changed }

@Serializable
enum class NoticeKind {
    Stopped, PausedBudget, PausedSteps, FailedKey, FailedNetwork, FailedProvider, FailedGeneric,
    ClaudeCodeSignedOut, ClaudeCodeLimit, ClaudeCodeFailed,
}

/** The task stopped because something went wrong, not because the user or a limit stopped it. */
val NoticeKind.isFailure: Boolean
    get() = this != NoticeKind.Stopped && this != NoticeKind.PausedBudget && this != NoticeKind.PausedSteps

/** One entry in the conversation, in the order the user reads it, as the screen draws it. */
@Serializable
sealed interface ThreadRow {
    val key: String

    @Serializable
    @SerialName("user")
    data class User(override val key: String, val text: String, val steer: Boolean, val attachments: List<AttachmentEntry> = emptyList()) : ThreadRow

    /** The agent's words; the last in a run is its [answer]. */
    @Serializable
    @SerialName("narration")
    data class Narration(override val key: String, val text: String, val streaming: Boolean, val answer: Boolean = false) : ThreadRow

    @Serializable
    @SerialName("steps")
    data class Steps(override val key: String, val steps: List<StepRow>) : ThreadRow

    @Serializable
    @SerialName("decision")
    data class Decision(override val key: String, val card: CardText) : ThreadRow

    @Serializable
    @SerialName("question")
    data class Question(override val key: String, val icon: String, val question: String, val options: List<String>, val answer: String? = null) : ThreadRow

    /** A card asking for a capability a tool needs, such as reading contacts; [capability] names it. */
    @Serializable
    @SerialName("capability")
    data class Capability(override val key: String, val capability: String, val card: CardText) : ThreadRow

    @Serializable
    @SerialName("resolved")
    data class Resolved(override val key: String, val outcome: String, val declined: Boolean) : ThreadRow

    /** A finished run's steps, folded into one chip. */
    @Serializable
    @SerialName("summary")
    data class Summary(override val key: String, val stepCount: Int, val seconds: Long, val expanded: Boolean, val folded: List<ThreadRow>) : ThreadRow

    @Serializable
    @SerialName("plan")
    data class Plan(override val key: String, val steps: List<PlanRow>) : ThreadRow

    /** A server or watcher the agent started; [fixName] is its name as an Ask to fix request may say it. */
    @Serializable
    @SerialName("process")
    data class Process(override val key: String, val name: String, val command: String, val fixName: String, val run: ProcessRun?) : ThreadRow

    /**
     * An app Whittle built. [installed] is the version installed, null when it isn't; [crash] its last crash; [fixFolder] its
     * project for Ask to fix, null when it can't be asked to fix.
     */
    @Serializable
    @SerialName("app")
    data class App(
        override val key: String,
        val name: String,
        val versionCode: Int,
        val powerful: List<PowerRow>,
        val installed: Int? = null,
        val installing: Boolean = false,
        val result: InstallOutcome? = null,
        val crash: String? = null,
        val fixName: String,
        val fixFolder: String? = null,
    ) : ThreadRow

    @Serializable
    @SerialName("sources")
    data class Sources(override val key: String, val sources: List<SourceRow>, val expanded: Boolean = false) : ThreadRow

    @Serializable
    @SerialName("notice")
    data class Notice(
        override val key: String,
        val kind: NoticeKind,
        val statusCode: Int? = null,
        val detail: String? = null,
        /** For a Claude plan limit: which one ("5-hour"), and when it reopens. */
        val limit: String? = null,
        val resetsAtMillis: Long? = null,
    ) : ThreadRow

    /** A run that was still working when the app closed. Continuing it is the user's call. */
    @Serializable
    @SerialName("interrupted")
    data class Interrupted(override val key: String) : ThreadRow
}
