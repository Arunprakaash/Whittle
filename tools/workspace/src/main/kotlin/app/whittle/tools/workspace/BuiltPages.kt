package app.whittle.tools.workspace

import java.io.File

/**
 * Pages build_web_app produced. Each build leaves a small record next to its output, listing the pages it
 * wrote; show_artifact only shows pages on such a list, so every app the user sees went through the build.
 */
internal object BuiltPages {
    private const val RECORD = ".whittle-build"

    fun record(outFolder: File, pageNames: Collection<String>) {
        File(outFolder, RECORD).writeText(pageNames.joinToString("\n"))
    }

    fun wasBuilt(page: File): Boolean {
        val record = File(page.parentFile ?: return false, RECORD).takeIf { it.isFile } ?: return false
        return page.name in record.readLines()
    }
}
