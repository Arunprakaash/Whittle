import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Puts a built web page (Whittle's browser version) in the app's assets under [folder], so the phone serves it itself
 * to the computers signed in to it.
 */
@CacheableTask
abstract class WebBuildAssetsTask : DefaultTask() {
    /** The page's files: its HTML, scripts, WebAssembly, fonts and resources. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val page: ConfigurableFileCollection

    @get:Input
    abstract val folder: Property<String>

    @get:OutputDirectory
    abstract val assetsDirectory: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(page)
            into(assetsDirectory.dir(folder))
            // Source maps only help a debugger; the phone has no use for them.
            exclude("**/*.map", "**/*.LICENSE.txt")
        }
    }
}
