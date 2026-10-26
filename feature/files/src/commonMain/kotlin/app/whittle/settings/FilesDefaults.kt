package app.whittle.settings

import kotlinx.serialization.Serializable

/** The file browser: what it hides, how much it shows, how it marks changes and colours code. */
@Serializable
data class FilesDefaults(
    /** Under the app's files folder: what the agent changed in each chat, kept for Changes. */
    val changesDirectoryName: String,
    /** Names never listed, such as Whittle's own folder of saved tool outputs. */
    val hiddenNames: List<String>,
    /** Longer files open as their start, with a note. */
    val maxPreviewChars: Int,
    val maxImageSidePx: Int,
    /** Unchanged lines kept around each change; longer runs fold. */
    val diffContextLines: Int,
    /** Past this many line pairs, a change shows as all removed then all added. */
    val maxDiffCells: Int,
    val folderIcon: String,
    val defaultFileIcon: String,
    /** By extension, lower case. */
    val fileIcons: Map<String, String>,
    val imageExtensions: List<String>,
    /** Prose, and files without an extension, show without code colours. */
    val proseExtensions: List<String>,
    /** Shown as their pages. */
    val pdfExtensions: List<String>,
    /** Coloured as keywords in code. */
    val keywords: List<String>,
    /** Line comment marker to the extensions that use it. */
    val lineComments: Map<String, List<String>>,
) {
    init {
        require(maxPreviewChars > 0 && maxImageSidePx > 0 && diffContextLines >= 0 && maxDiffCells > 0) { "files limits must be positive" }
    }

    fun iconFor(name: String, directory: Boolean): String =
        if (directory) folderIcon else fileIcons[name.substringAfterLast('.', "").lowercase()] ?: defaultFileIcon

    fun commentFor(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return lineComments.entries.firstOrNull { extension in it.value }?.key
    }

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in imageExtensions

    fun isPdf(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in pdfExtensions

    fun isCode(name: String): Boolean = name.substringAfterLast('.', "").lowercase().let { it.isNotEmpty() && it !in proseExtensions }
}
