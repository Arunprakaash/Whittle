package app.whittle.apps

/** What the built APK's manifest says, as Android's own parser read it. */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val requestedPermissions: List<String>,
    /** Permissions that components (services, receivers, activities, providers) demand of their callers. */
    val componentPermissions: List<String>,
    val sharedUserId: Boolean,
    /** The SDK levels the APK declares, which Whittle sets at build time. */
    val minSdk: Int,
    val targetSdk: Int,
)

/** What an app's manifest declares that Whittle shows or refuses. */
data class ManifestReport(
    val packageName: String,
    val permissions: List<String>,
    val powerful: List<PowerfulDeclaration>,
    val sharedUserId: Boolean,
)

object AppManifest {
    /** Which of [powerful] the app declares: permissions it requests, or service permissions its components use. */
    fun classify(facts: ApkFacts, powerful: List<PowerfulDeclaration>): ManifestReport = ManifestReport(
        facts.packageName,
        facts.requestedPermissions.distinct(),
        powerful.filter {
            when (it.kind) {
                PowerfulKind.PERMISSION -> it.name in facts.requestedPermissions
                PowerfulKind.SERVICE_PERMISSION -> it.name in facts.componentPermissions
            }
        },
        facts.sharedUserId,
    )
}
