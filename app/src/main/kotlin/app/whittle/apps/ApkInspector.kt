package app.whittle.apps

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import java.io.File

/** Reads what a built APK declares. */
fun interface ApkInspector {
    fun inspect(apk: File): Outcome<ApkFacts>
}

/** What an [info] from Android's package parser declares, for an APK file or an installed app alike. */
fun apkFactsOf(info: PackageInfo): ApkFacts {
    val components = info.services.orEmpty().map { it.permission } + info.receivers.orEmpty().map { it.permission } +
        info.activities.orEmpty().map { it.permission } +
        info.providers.orEmpty().flatMap { listOf(it.readPermission, it.writePermission) }
    val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    return ApkFacts(info.packageName, version, info.requestedPermissions.orEmpty().toList(), components.filterNotNull(), info.sharedUserId != null)
}

/** The flags that make Android's parser report permissions and components. */
const val FACT_FLAGS = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
    PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS

/** Asks Android's own parser, so what is checked is what the system would install. */
class AndroidApkInspector(private val packageManager: PackageManager) : ApkInspector {
    override fun inspect(apk: File): Outcome<ApkFacts> {
        val info = packageManager.getPackageArchiveInfo(apk.path, FACT_FLAGS)
            ?: return Outcome.Failure(HarnessError.Storage("Android couldn't read ${apk.name}"))
        return Outcome.Success(apkFactsOf(info))
    }
}
