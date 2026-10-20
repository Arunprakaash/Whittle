import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Puts one file (another module's build output, e.g. an APK) into generated assets at [assetPath]. */
@CacheableTask
abstract class FileAssetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    /** Path inside assets, e.g. export/shell.apk. */
    @get:Input
    abstract val assetPath: Property<String>

    @get:OutputDirectory
    abstract val assetsDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val root = assetsDirectory.get().asFile
        root.deleteRecursively()
        val target = root.resolve(assetPath.get())
        target.parentFile.mkdirs()
        source.get().asFile.copyTo(target, overwrite = true)
    }
}
