package app.whittle.apps

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import app.whittle.WhittleApplication
import java.security.cert.X509Certificate

/**
 * Where the generated apps' crash reports arrive. It is exported with no permission, because a generated app has its own key and
 * can't hold one of Whittle's; [AppCrashes] decides who is heard. Nothing can be read, changed or deleted through it.
 * `onCreate` runs before the application's, so the app graph is reached only when a report arrives.
 */
class AppCrashProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val context = context ?: return null
        val application = context.applicationContext as? WhittleApplication ?: return null
        val uidPackages = context.packageManager.getPackagesForUid(Binder.getCallingUid())?.toList()
        val accepted = application.graph.appCrashes.report(
            callingPackage,
            uidPackages,
            values?.getAsString("package"),
            values?.getAsString("stack"),
        )
        return if (accepted) uri else null
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
