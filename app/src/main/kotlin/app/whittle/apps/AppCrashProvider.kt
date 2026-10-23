package app.whittle.apps

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import app.whittle.WhittleApplication
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.getOrNull
import app.whittle.core.config.ConfigLoader
import java.security.cert.X509Certificate

/**
 * Where the generated apps' crash reports arrive. It is exported with no permission, because a generated app has its own key and
 * can't hold one of Whittle's; [AppCrashes] decides who is heard. Nothing can be read, changed or deleted through it.
 * `onCreate` runs before the application's, so the app graph is reached only when a report arrives, and only for a caller that
 * passed the checks that need no keys.
 */
class AppCrashProvider : ContentProvider() {
    /** The package prefix of Whittle's apps, read from the bundled config without starting the app graph. */
    private val prefix: String? by lazy {
        ConfigLoader(BundledConfigSource()).load(NativeAppsConfig.FILE, NativeAppsConfig.serializer(), NativeAppsConfig.SCHEMA_VERSION).getOrNull()?.packagePrefix
    }

    override fun onCreate(): Boolean = true

    /** Never throws: a report that can't be checked or kept is dropped. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = try {
        if (accept(values)) uri else null
    } catch (_: Exception) {
        null
    }

    private fun accept(values: ContentValues?): Boolean {
        val context = context ?: return false
        val caller = callingPackage
        val uidPackages = context.packageManager.getPackagesForUid(Binder.getCallingUid())?.toList()
        val claimed = values?.getAsString("package")
        val prefix = prefix ?: return false
        if (!isWhittleCaller(caller, uidPackages, claimed, prefix)) return false
        val application = context.applicationContext as? WhittleApplication ?: return false
        return application.graph.appCrashes.report(caller, uidPackages, claimed, values?.getAsString("stack"))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? = null
}

/**
 * The certificate [packageName] is signed with, or null unless it has exactly one signer: from `signingInfo` on Android 9 and
 * later (refusing several signers or a rotated history), from `signatures` below that.
 */
@Suppress("DEPRECATION")
fun signerOf(packageManager: PackageManager, packageName: String): X509Certificate? = try {
    val encoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        if (info == null) {
            null
        } else {
            soleSigner(
                info.apkContentsSigners?.map { it.toByteArray() },
                info.signingCertificateHistory?.map { it.toByteArray() },
                info.hasMultipleSigners(),
            )
        }
    } else {
        val signatures = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        soleSigner(signatures?.map { it.toByteArray() }, history = null, multipleSigners = false)
    }
    encoded?.let(::certificateFromBytes)
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: RuntimeException) {
    null
}
