plugins {
    alias(libs.plugins.mobileagent.android.library)
}

android {
    namespace = "com.mobileagent.observability.posthog"
}

dependencies {
    api(project(":observability:api"))
    implementation(libs.posthog.android)
}
