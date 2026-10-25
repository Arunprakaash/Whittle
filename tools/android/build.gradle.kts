plugins {
    alias(libs.plugins.whittle.android.library)
}

android {
    namespace = "app.whittle.tools.android"
}

dependencies {
    api(project(":tools:api"))
    // Health Connect: the app asks for its permissions with the library's own contract, so it's part of this module's API.
    api(libs.androidx.health.connect)
    implementation(libs.kotlinx.serialization.json)
}
