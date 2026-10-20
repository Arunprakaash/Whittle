plugins {
    alias(libs.plugins.whittle.android.library)
}

android {
    namespace = "app.whittle.observability.posthog"
}

dependencies {
    api(project(":observability:api"))
    implementation(libs.posthog.android)
}
