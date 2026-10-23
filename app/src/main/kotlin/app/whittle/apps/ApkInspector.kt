package app.whittle.apps

import android.content.pm.PackageManager
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import java.io.File

/** Reads what a built APK declares. */
fun interface ApkInspector {
    fun inspect(apk: File): Outcome<ApkFacts>
}

/** Asks Android's own parser, so what is checked is what the system would install. */
class AndroidApkInspector(private val packageManager: PackageManager) : ApkInspector {
    override fun inspect(apk: File): Outcome<ApkFacts> {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS
        val info = packageManager.getPackageArchiveInfo(apk.path, flags)
            ?: return Outcome.Failure(HarnessError.Storage("Android couldn't read ${apk.name}"))
        val components = info.services.orEmpty().map { it.permission } + info.receivers.orEmpty().map { it.permission } +
            info.activities.orEmpty().map { it.permission } +
            info.providers.orEmpty().flatMap { listOf(it.readPermission, it.writePermission) }
        return Outcome.Success(
            ApkFacts(info.packageName, info.requestedPermissions.orEmpty().toList(), components.filterNotNull(), info.sharedUserId != null),
        )
    }
}
