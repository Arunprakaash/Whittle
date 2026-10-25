import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Pins the Termux packages named in the spec, and everything they depend on, to exact versions and SHA-256s
 * from Termux's current package index. Run by hand (./gradlew :app:updateTermuxLock) to update the tools;
 * builds only ever download what the lock file names. Termux keeps only the latest version of each package,
 * so a build failing to download a pinned file means it's time to run this again.
 */
@DisableCachingByDefault(because = "Reads Termux's live package index")
abstract class UpdateTermuxLockTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specFile: RegularFileProperty

    @get:OutputFile
    abstract val lockFile: RegularFileProperty

    @TaskAction
    fun update() {
        val spec = TermuxSpec.read(specFile.get().asFile)
        val lock = spec.abis.toSortedMap().mapValues { (_, arch) ->
            val url = "${spec.repository.trimEnd('/')}/dists/stable/main/binary-$arch/Packages"
            val index = TermuxIndex.parse(String(Debs.download(url, spec)))
            index.resolve(spec.packages).map { mapOf("name" to it.name, "version" to it.version, "filename" to it.filename, "sha256" to it.sha256) }
        }
        lockFile.get().asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(lock)) + "\n")
        lock.forEach { (abi, packages) -> logger.lifecycle("$abi: ${packages.joinToString { "${it["name"]} ${it["version"]}" }}") }
    }
}
