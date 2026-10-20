package app.whittle.runtimes

import android.content.Context
import app.whittle.BuildConfig
import app.whittle.tools.workspace.CommandLauncher
import java.io.File

/**
 * Every command starts through the sandbox program in the native-library folder (app/src/main/native/sandbox):
 * its own process group, no sockets unless the command was allowed the network, no access to other
 * processes' memory. If the program is missing, starting it fails and so does the command: commands never
 * run unsandboxed.
 */
object CommandSandbox {
    private const val NETWORK_FLAG = "--network"
    private const val END_OF_OPTIONS = "--"

    fun launcher(context: Context): CommandLauncher {
        val program = File(context.applicationInfo.nativeLibraryDir, BuildConfig.SANDBOX_PROGRAM).path
        return CommandLauncher { network -> listOfNotNull(program, NETWORK_FLAG.takeIf { network }, END_OF_OPTIONS) }
    }
}
