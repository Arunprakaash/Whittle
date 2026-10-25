package app.whittle.tools.android

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where the storage tools look. They go through this so a MediaStore-backed listing can replace the
 * plain File one without touching the tools, should the phone check show File can't see shared storage.
 */
interface SharedStorage {
    /** The top of shared storage (what Android calls /sdcard), or null when [AndroidCapabilities.STORAGE] isn't granted. */
    fun root(): File?

    /** True when [root] can be listed and read. */
    fun canRead(): Boolean = root()?.canRead() == true
}

/** Shared storage as the File API sees it, once [AndroidCapabilities.STORAGE] is granted. */
class DeviceSharedStorage(private val context: Context) : SharedStorage {
    override fun root(): File? =
        if (AndroidCapabilities.isGranted(context, AndroidCapabilities.STORAGE)) Environment.getExternalStorageDirectory() else null
}
