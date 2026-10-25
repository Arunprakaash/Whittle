package app.whittle.agent

import app.whittle.core.common.getOrElse
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.PromptTemplate
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable

/**
 * Text the harness sends to the model as tool results. Kept as reviewed config, not code.
 * Each template may only use the placeholders listed for it; this is checked when the file loads.
 */
@Serializable
data class AgentMessages(
    override val schemaVersion: Int,
    val unknownTool: String,
    val invalidArguments: String,
    val policyDenied: String,
    /** Tool result for a call parked for the user's choice. */
    val awaitingUser: String,
    /** Harness note once the user approves a parked call and it runs. */
    val decisionApproved: String,
    /** Same, when the tool's output came from outside the phone and must stay information only. */
    val decisionApprovedUntrusted: String,
    val decisionDeclined: String,
    val decisionFailed: String,
    /** Tool result for a question shown to the user as a card. */
    val questionShown: String,
    val questionAnswered: String,
    /** Tool result when a tool needs a capability the user hasn't granted. */
    val capabilityMissing: String,
    val capabilityGranted: String,
    val capabilityDenied: String,
    /** The user allowed a capability but setting it up failed; [REASON] says why. Not a refusal: they may ask again. */
    val capabilitySetupFailed: String,
    val interruptedToolCall: String,
    val toolFailed: String,
    val outputTruncated: String,
    /** Sent once when the model tries to finish while its plan still has open steps. */
    val planUnfinished: String,
    /** A long tool output's start and end, with where the whole of it was saved in the workspace. */
    val outputSaved: String,
    /** One remembered fact in the system prompt, with the id forget takes. */
    val memoryLine: String,
    /** The system prompt's memory section when nothing is remembered yet. */
    val noMemories: String,
    /** remember succeeded; [ID] is what forget takes. */
    val memorySaved: String,
    val memoryEmpty: String,
    val memoryTooLong: String,
    val memoryFull: String,
    val memoryForgotten: String,
    val memoryNotFound: String,
    /** schedule_task saved an automation; [PLACE] is [automationInProject] or [automationInWorkspace]. */
    val automationScheduled: String,
    val automationInProject: String,
    val automationInWorkspace: String,
    val automationEveryDay: String,
    val automationWeekdays: String,
    val automationWeekends: String,
    /** Stands in for the list of tools an automation may use without asking, when there are none. */
    val automationNoTools: String,
    val automationNameEmpty: String,
    val automationNameTooLong: String,
    val automationPromptEmpty: String,
    val automationPromptTooLong: String,
    val automationTooMany: String,
    val automationToolNotAllowed: String,
    val automationProjectInvalid: String,
    val automationProjectMissing: String,
    val automationTimeInvalid: String,
    val automationDaysInvalid: String,
    val automationSaveFailed: String,
    /** Added to the user's message when they attach files: [FILES] is one [attachmentLine] each. */
    val attachments: String,
    val attachmentLine: String,
    /** Added after [attachments] when the model can't see images, so it only gets them as files. */
    val attachmentImagesUnseen: String,
    /** Stands in for an attached image that can no longer be read from the workspace. */
    val imageUnavailable: String,
    /** create_app made a project; [NAME], [PROJECT] (its folder) and [PACKAGE] say which. */
    val appCreated: String,
    val appNameEmpty: String,
    val appNoWorkspace: String,
    /** create_app or build_app in a chat confined to [PROJECT], reaching outside it; worded like use_project's refusal. */
    val appConfined: String,
    val appNoProject: String,
    val appSharedUserId: String,
    val appPackageMismatch: String,
    val appBuildFailed: String,
    val appBuildLogSaved: String,
    val appBuildNoApk: String,
    val appBuildTimedOut: String,
    /** build_app's APK carries a version other than the one Whittle set. */
    val appVersionMismatch: String,
    /** build_app's APK carries a minimum or target SDK other than the ones Whittle sets. */
    val appSdkMismatch: String,
    /** The reason given with [capabilitySetupFailed] when app building can't be set up because Linux isn't. */
    val appBuildingNeedsLinux: String,
    /** Why app building's setup failed, one per kind; any raw output follows on its own line. */
    val appSetupTimedOut: String,
    val appSetupInterrupted: String,
    val appSetupNotConfirmed: String,
    val appSetupNotStarted: String,
    val appSetupInUse: String,
    val appSetupOutput: String,
    /**
     * Output the user passed on with Ask to fix (a crash, a process's output, a preview's console), sent beside their request as
     * untrusted content; [SOURCE] is one of the three below.
     */
    val diagnosticsUntrusted: String,
    val diagnosticsFromAppCrash: String,
    val diagnosticsFromProcess: String,
    val diagnosticsFromPreview: String,
    val diagnosticsFromScreen: String,
    /** The name [diagnosticsFromScreen] uses when Android didn't say which app was on screen. */
    val screenUnknownApp: String,
    /** Names the screenshot at [PATH], of [SOURCE], as data; it goes beside it, so a model that sees images reads both. */
    val screenshotUntrusted: String,
) : VersionedConfig {
    init {
        requirePlaceholders(attachments, FILES)
        requirePlaceholders(attachmentLine, PATH, SIZE)
        requirePlaceholders(attachmentImagesUnseen)
        requirePlaceholders(imageUnavailable, PATH)
        requirePlaceholders(unknownTool, TOOL)
        requirePlaceholders(invalidArguments, TOOL, DETAIL)
        requirePlaceholders(policyDenied, TOOL)
        requirePlaceholders(awaitingUser, TOOL)
        requirePlaceholders(decisionApproved, TOOL, OUTPUT)
        requirePlaceholders(decisionApprovedUntrusted, TOOL, SOURCE, OUTPUT)
        requirePlaceholders(decisionDeclined, TOOL)
        requirePlaceholders(decisionFailed, TOOL, DETAIL)
        requirePlaceholders(questionShown, TOOL)
        requirePlaceholders(questionAnswered, QUESTION, ANSWER)
        requirePlaceholders(capabilityMissing, TOOL, CAPABILITY)
        requirePlaceholders(capabilityGranted, CAPABILITY)
        requirePlaceholders(capabilityDenied, CAPABILITY)
        requirePlaceholders(capabilitySetupFailed, CAPABILITY, REASON)
        requirePlaceholders(interruptedToolCall, TOOL)
        requirePlaceholders(toolFailed, TOOL, DETAIL)
        requirePlaceholders(outputTruncated, LIMIT)
        requirePlaceholders(planUnfinished, STEPS)
        requirePlaceholders(outputSaved, HEAD, TAIL, CHARS, PATH)
        requirePlaceholders(memoryLine, ID, TEXT)
        requirePlaceholders(noMemories)
        requirePlaceholders(memorySaved, ID)
        requirePlaceholders(memoryEmpty)
        requirePlaceholders(memoryTooLong, LIMIT)
        requirePlaceholders(memoryFull, LIMIT)
        requirePlaceholders(memoryForgotten)
        requirePlaceholders(memoryNotFound, ID)
        requirePlaceholders(automationScheduled, NAME, TIME, DAYS, NEXT, PLACE, TOOLS)
        requirePlaceholders(automationInProject, PROJECT)
        requirePlaceholders(automationInWorkspace)
        requirePlaceholders(automationEveryDay)
        requirePlaceholders(automationWeekdays)
        requirePlaceholders(automationWeekends)
        requirePlaceholders(automationNoTools)
        requirePlaceholders(automationNameEmpty)
        requirePlaceholders(automationNameTooLong, LIMIT)
        requirePlaceholders(automationPromptEmpty)
        requirePlaceholders(automationPromptTooLong, LIMIT)
        requirePlaceholders(automationTooMany, LIMIT)
        requirePlaceholders(automationToolNotAllowed)
        requirePlaceholders(automationProjectInvalid, PROJECT)
        requirePlaceholders(automationProjectMissing, PROJECT)
        requirePlaceholders(automationTimeInvalid)
        requirePlaceholders(automationDaysInvalid)
        requirePlaceholders(automationSaveFailed, DETAIL)
        requirePlaceholders(appCreated, NAME, PROJECT, PACKAGE)
        requirePlaceholders(appNameEmpty)
        requirePlaceholders(appNoWorkspace)
        requirePlaceholders(appConfined, PROJECT)
        requirePlaceholders(appNoProject, PROJECT)
        requirePlaceholders(appSharedUserId)
        requirePlaceholders(appPackageMismatch, EXPECTED)
        requirePlaceholders(appBuildFailed, ERRORS)
        requirePlaceholders(appBuildLogSaved, PATH)
        requirePlaceholders(appBuildNoApk)
        requirePlaceholders(appBuildTimedOut, SECONDS)
        requirePlaceholders(appVersionMismatch)
        requirePlaceholders(appSdkMismatch)
        requirePlaceholders(appBuildingNeedsLinux)
        requirePlaceholders(appSetupTimedOut)
        requirePlaceholders(appSetupInterrupted)
        requirePlaceholders(appSetupNotConfirmed)
        requirePlaceholders(appSetupNotStarted)
        requirePlaceholders(appSetupInUse)
        requirePlaceholders(appSetupOutput)
        requirePlaceholders(diagnosticsUntrusted, SOURCE, TEXT)
        requirePlaceholders(diagnosticsFromAppCrash, NAME)
        requirePlaceholders(diagnosticsFromProcess, NAME)
        requirePlaceholders(diagnosticsFromPreview, NAME)
        requirePlaceholders(diagnosticsFromScreen, NAME)
        requirePlaceholders(screenshotUntrusted, SOURCE, PATH)
    }

    fun unknownTool(tool: String): String = render(unknownTool, TOOL to tool)

    fun invalidArguments(tool: String, detail: String): String = render(invalidArguments, TOOL to tool, DETAIL to detail)

    fun policyDenied(tool: String): String = render(policyDenied, TOOL to tool)

    fun awaitingUser(tool: String): String = render(awaitingUser, TOOL to tool)

    fun decisionApproved(tool: String, output: String): String = render(decisionApproved, TOOL to tool, OUTPUT to output)

    fun decisionApprovedUntrusted(tool: String, source: String, output: String): String =
        render(decisionApprovedUntrusted, TOOL to tool, SOURCE to source, OUTPUT to output)

    fun decisionDeclined(tool: String): String = render(decisionDeclined, TOOL to tool)

    fun decisionFailed(tool: String, detail: String): String = render(decisionFailed, TOOL to tool, DETAIL to detail)

    fun questionShown(tool: String): String = render(questionShown, TOOL to tool)

    fun questionAnswered(question: String, answer: String): String = render(questionAnswered, QUESTION to question, ANSWER to answer)

    fun capabilityMissing(tool: String, capability: String): String = render(capabilityMissing, TOOL to tool, CAPABILITY to capability)

    fun capabilityGranted(capability: String): String = render(capabilityGranted, CAPABILITY to capability)

    fun capabilityDenied(capability: String): String = render(capabilityDenied, CAPABILITY to capability)

    fun capabilitySetupFailed(capability: String, reason: String): String =
        render(capabilitySetupFailed, CAPABILITY to capability, REASON to reason)

    fun interruptedToolCall(tool: String): String = render(interruptedToolCall, TOOL to tool)

    fun toolFailed(tool: String, detail: String): String = render(toolFailed, TOOL to tool, DETAIL to detail)

    fun outputTruncated(limit: Int): String = render(outputTruncated, LIMIT to limit.toString())

    private fun render(template: String, vararg variables: Pair<String, String>): String =
        PromptTemplate(ConfigFiles.agentMessages, template).render(variables.toMap())
            .getOrElse { error("Unreachable: placeholders are validated in init (${it.message})") }

    private fun requirePlaceholders(template: String, vararg allowed: String) {
        val unknown = PromptTemplate(ConfigFiles.agentMessages, template).placeholders - allowed.toSet()
        require(unknown.isEmpty()) { "Unknown placeholders $unknown in agent message: $template" }
    }

    fun planUnfinished(steps: String): String = render(planUnfinished, STEPS to steps)

    fun outputSaved(head: String, tail: String, chars: Int, path: String): String =
        render(outputSaved, HEAD to head, TAIL to tail, CHARS to chars.toString(), PATH to path)


    fun memorySaved(id: String): String = render(memorySaved, ID to id)

    fun memoryTooLong(limit: Int): String = render(memoryTooLong, LIMIT to limit.toString())

    fun memoryFull(limit: Int): String = render(memoryFull, LIMIT to limit.toString())

    fun memoryNotFound(id: String): String = render(memoryNotFound, ID to id)

    fun automationScheduled(name: String, time: String, days: String, next: String, place: String, tools: String): String =
        render(automationScheduled, NAME to name, TIME to time, DAYS to days, NEXT to next, PLACE to place, TOOLS to tools)

    fun automationInProject(project: String): String = render(automationInProject, PROJECT to project)

    fun automationNameTooLong(limit: Int): String = render(automationNameTooLong, LIMIT to limit.toString())

    fun automationPromptTooLong(limit: Int): String = render(automationPromptTooLong, LIMIT to limit.toString())

    fun automationTooMany(limit: Int): String = render(automationTooMany, LIMIT to limit.toString())

    fun automationProjectInvalid(project: String): String = render(automationProjectInvalid, PROJECT to project)

    fun automationProjectMissing(project: String): String = render(automationProjectMissing, PROJECT to project)

    fun automationSaveFailed(detail: String): String = render(automationSaveFailed, DETAIL to detail)

    /** The note on a message with attachments, from (path, size) pairs; [imagesUnseen] when the model can't see them. */
    fun attachments(files: List<Pair<String, String>>, imagesUnseen: Boolean): String {
        val list = files.joinToString("\n") { (path, size) -> render(attachmentLine, PATH to path, SIZE to size) }
        return listOfNotNull(render(attachments, FILES to list), attachmentImagesUnseen.takeIf { imagesUnseen }).joinToString("\n")
    }

    fun imageUnavailable(path: String): String = render(imageUnavailable, PATH to path)

    fun appCreated(name: String, project: String, packageName: String): String =
        render(appCreated, NAME to name, PROJECT to project, PACKAGE to packageName)

    fun appConfined(project: String): String = render(appConfined, PROJECT to project)

    fun appNoProject(project: String): String = render(appNoProject, PROJECT to project)

    fun appPackageMismatch(expected: String): String = render(appPackageMismatch, EXPECTED to expected)

    fun appBuildFailed(errors: String): String = render(appBuildFailed, ERRORS to errors)

    fun appBuildTimedOut(seconds: Int): String = render(appBuildTimedOut, SECONDS to seconds.toString())

    fun appBuildLogSaved(path: String): String = render(appBuildLogSaved, PATH to path)

    fun diagnosticsUntrusted(source: String, text: String): String = render(diagnosticsUntrusted, SOURCE to source, TEXT to text)

    fun diagnosticsFromAppCrash(name: String): String = render(diagnosticsFromAppCrash, NAME to name)

    fun diagnosticsFromProcess(name: String): String = render(diagnosticsFromProcess, NAME to name)

    fun diagnosticsFromPreview(name: String): String = render(diagnosticsFromPreview, NAME to name)

    fun diagnosticsFromScreen(name: String): String = render(diagnosticsFromScreen, NAME to name)

    fun screenshotUntrusted(source: String, path: String): String = render(screenshotUntrusted, SOURCE to source, PATH to path)

    /** The memory section of the system prompt: one line per remembered fact, as (id, text) pairs. */
    fun memories(items: List<Pair<String, String>>): String =
        if (items.isEmpty()) noMemories else items.joinToString("\n") { (id, text) -> render(memoryLine, ID to id, TEXT to text) }

    companion object {
        const val SCHEMA_VERSION = 13
        private const val FILES = "files"
        private const val SIZE = "size"
        private const val TOOL = "tool"
        private const val PACKAGE = "package"
        private const val EXPECTED = "expected"
        private const val ERRORS = "errors"
        private const val SECONDS = "seconds"
        private const val DETAIL = "detail"
        private const val LIMIT = "limit"
        private const val OUTPUT = "output"
        private const val SOURCE = "source"
        private const val QUESTION = "question"
        private const val ANSWER = "answer"
        private const val CAPABILITY = "capability"
        private const val REASON = "reason"
        private const val STEPS = "steps"
        private const val HEAD = "head"
        private const val TAIL = "tail"
        private const val CHARS = "chars"
        private const val PATH = "path"
        private const val ID = "id"
        private const val TEXT = "text"
        private const val NAME = "name"
        private const val TIME = "time"
        private const val DAYS = "days"
        private const val NEXT = "next"
        private const val PLACE = "place"
        private const val TOOLS = "tools"
        private const val PROJECT = "project"
    }
}
