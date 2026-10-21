import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Compiles one C file into a program for each ABI with the NDK's clang, named like a native library
 * (lib<name>.so) so it ships in the APK's native-library folder: the only place Android lets an app run
 * native programs from.
 */
@CacheableTask
abstract class NativeExecutableTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val ndkDirectory: DirectoryProperty

    /** ABI (e.g. arm64-v8a) to clang target triple (e.g. aarch64-linux-android). */
    @get:Input
    abstract val targets: MapProperty<String, String>

    @get:Input
    abstract val minSdk: Property<Int>

    /** The program's file name, without the lib prefix and .so suffix. */
    @get:Input
    abstract val programName: Property<String>

    /**
     * Linked statically, with no dependency on the system's libc or dynamic linker, so it also runs inside a Linux
     * root filesystem, whose libc is glibc.
     */
    @get:Input
    abstract val linkStatically: Property<Boolean>

    @get:OutputDirectory
    abstract val jniLibsDirectory: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun compile() {
        val prebuilt = ndkDirectory.get().asFile.resolve("toolchains/llvm/prebuilt")
        val host = prebuilt.listFiles()?.firstOrNull { it.isDirectory }
            ?: throw GradleException("No NDK LLVM toolchain under $prebuilt")
        val clang = host.resolve("bin/clang")
        val linking = if (linkStatically.get()) listOf("-static", "-s") else listOf("-fPIE", "-pie")
        targets.get().forEach { (abi, triple) ->
            val output = jniLibsDirectory.get().dir(abi).file("lib${programName.get()}.so").asFile
            output.parentFile.mkdirs()
            exec.exec {
                commandLine(
                    clang.path,
                    "--target=$triple${minSdk.get()}",
                    "-O2", "-Wall", "-Wextra", "-Werror",
                    *linking.toTypedArray(), "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                    "-o", output.path,
                    source.get().asFile.path,
                )
            }
        }
    }
}
