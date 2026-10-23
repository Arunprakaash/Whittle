package app.whittle.apps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The last crash of each app Whittle built (docs/native-apps.md, Crashes). A report counts only when the caller is a package
 * under [prefix] whose signing certificate is the key Whittle holds for it, so the apps that can be heard are few and Whittle's
 * own. The last stack of each, cut to [maxChars], is kept in [file] so one that started Whittle's process only to be recorded
 * survives the process being killed.
 */
class AppCrashes(
    private val expectedCertificate: (String) -> X509Certificate?,
    private val signerOf: (String) -> X509Certificate?,
    private val prefix: String,
    private val maxChars: Int,
    private val file: File,
) {
    private val lock = Any()
    private val _latest = MutableStateFlow(load())

    /** The last crash's stack by package. */
    val latest: StateFlow<Map<String, String>> = _latest.asStateFlow()

    /** Keeps [stack] as [callingPackage]'s last crash when that package is one of Whittle's, signed with its own key. */
    fun accept(callingPackage: String?, stack: String): Boolean {
        if (callingPackage == null || !callingPackage.startsWith(prefix) || stack.isBlank()) return false
        val expected = expectedCertificate(callingPackage) ?: return false
        val actual = signerOf(callingPackage) ?: return false
        if (!sameCertificate(expected, actual)) return false
        synchronized(lock) {
            val next = _latest.value + (callingPackage to stack.take(maxChars))
            persist(next)
            _latest.value = next
        }
        return true
    }

    /**
     * What the provider hands over: the caller Android reports, the packages that share the calling uid, and the two columns.
     * The caller must be one of that uid's packages, and the `package` column must name it.
     */
    fun report(callingPackage: String?, uidPackages: Collection<String>?, claimedPackage: String?, stack: String?): Boolean {
        if (callingPackage == null || uidPackages == null || callingPackage !in uidPackages) return false
        if (claimedPackage != callingPackage || stack.isNullOrBlank()) return false
        return accept(callingPackage, stack)
    }

    private fun load(): Map<String, String> = try {
        if (file.isFile) {
            Json.decodeFromString(SERIALIZER, file.readText()).filterKeys { it.startsWith(prefix) }.mapValues { it.value.take(maxChars) }
        } else {
            emptyMap()
        }
    } catch (_: IOException) {
        emptyMap()
    } catch (_: IllegalArgumentException) {
        emptyMap()
    }

    /** Writes a temporary file and renames it over [file], so a kill in the middle leaves the old one whole. */
    private fun persist(crashes: Map<String, String>) {
        try {
            val temporary = File(file.parentFile, file.name + TEMPORARY)
            temporary.writeText(Json.encodeToString(SERIALIZER, crashes))
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: IOException) {
            // The crash still shows until the process ends; a full disk shouldn't make the provider throw.
        }
    }

    companion object {
        const val FILE_NAME = "app-crashes.json"
        private const val TEMPORARY = ".tmp"
        private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}

/** Two certificates are the same key when their encoded bytes are equal. */
fun sameCertificate(a: X509Certificate, b: X509Certificate): Boolean = try {
    a.encoded.contentEquals(b.encoded)
} catch (_: CertificateException) {
    false
}

/**
 * The one signing certificate of an app, from the encoded [current] certificates, or null. An app with several signers, or one
 * whose signing history holds more than one certificate (a rotated key), is refused: Whittle signed it with a single key.
 * [history] is null where the platform has no lineage (below Android 9).
 */
fun soleSigner(current: List<ByteArray>?, history: List<ByteArray>?, multipleSigners: Boolean): ByteArray? {
    if (multipleSigners || current == null || current.size != 1) return null
    if (history != null && history.size > 1) return null
    return current.single()
}

/** The certificate in [encoded], or null when it isn't one. */
fun certificateFromBytes(encoded: ByteArray): X509Certificate? = try {
    CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as? X509Certificate
} catch (_: CertificateException) {
    null
}
