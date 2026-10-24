import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Puts every .deb the lock file pins in [outputDirectory], verified, for uploading as Whittle's mirror (the
 * termux-runtimes.json `mirrors`). CI uploads them from main, so a version Termux drops later can still be built.
 */
abstract class CollectTermuxDebsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockFile: RegularFileProperty

    /** Gradle's shared download cache; it outlives the build directory. */
    @get:Internal
    abstract val downloadDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun collect() {
        val spec = TermuxSpec.read(specFile.get().asFile)
        val lock = Lockfile.read(lockFile.get().asFile)
        val output = outputDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        val pinned = lock.values.flatten().distinctBy { it.filename }
        pinned.forEach { locked ->
            output.resolve(locked.filename.substringAfterLast('/')).writeBytes(Debs.fetch(spec, locked, downloadDirectory.get().asFile))
        }
        logger.lifecycle("${pinned.size} pinned packages in $output")
    }
}
