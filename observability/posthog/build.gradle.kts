plugins {
    alias(libs.plugins.whittle.android.library)
}

android {
    namespace = "app.whittle.observability.posthog"
}

dependencies {
    api(project(":observability:api"))
    implementation(libs.posthog.android)
    // Only for the Modifier type in hiddenFromReplay; no Compose UI is built here.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
}
